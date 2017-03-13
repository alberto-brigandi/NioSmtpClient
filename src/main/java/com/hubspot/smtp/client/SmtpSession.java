package com.hubspot.smtp.client;

import static io.netty.handler.codec.smtp.LastSmtpContent.EMPTY_LAST_CONTENT;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Sets;
import com.hubspot.smtp.messages.MessageContent;
import com.hubspot.smtp.utils.SmtpResponses;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.smtp.DefaultSmtpRequest;
import io.netty.handler.codec.smtp.SmtpCommand;
import io.netty.handler.codec.smtp.SmtpRequest;
import io.netty.handler.codec.smtp.SmtpResponse;
import io.netty.handler.ssl.SslHandler;

public class SmtpSession {
  // https://tools.ietf.org/html/rfc2920#section-3.1
  // In particular, the commands RSET, MAIL FROM, SEND FROM, SOML FROM, SAML FROM,
  // and RCPT TO can all appear anywhere in a pipelined command group.
  private static final Set<SmtpCommand> VALID_ANYWHERE_PIPELINED_COMMANDS = Sets.newHashSet(
      SmtpCommand.RSET, SmtpCommand.MAIL, SmtpCommand.RCPT);

  // https://tools.ietf.org/html/rfc2920#section-3.1
  // The EHLO, DATA, VRFY, EXPN, TURN, QUIT, and NOOP commands can only appear
  // as the last command in a group since their success or failure produces
  // a change of state which the client SMTP must accommodate.
  private static final Set<SmtpCommand> VALID_AT_END_PIPELINED_COMMANDS = Sets.newHashSet(
      SmtpCommand.RSET,
      SmtpCommand.MAIL,
      SmtpCommand.RCPT,
      SmtpCommand.EHLO,
      SmtpCommand.DATA,
      SmtpCommand.VRFY,
      SmtpCommand.EXPN,
      SmtpCommand.QUIT,
      SmtpCommand.NOOP);

  private static final Joiner COMMA_JOINER = Joiner.on(", ");
  private static final SmtpCommand STARTTLS_COMMAND = SmtpCommand.valueOf("STARTTLS");

  private final Channel channel;
  private final ResponseHandler responseHandler;
  private final ExecutorService executorService;
  private final SmtpSessionConfig config;
  private final CompletableFuture<Void> closeFuture;

  private volatile EnumSet<SupportedExtensions> supportedExtensions = EnumSet.noneOf(SupportedExtensions.class);

  SmtpSession(Channel channel, ResponseHandler responseHandler, ExecutorService executorService, SmtpSessionConfig config) {
    this.channel = channel;
    this.responseHandler = responseHandler;
    this.executorService = executorService;
    this.config = config;
    this.closeFuture = new CompletableFuture<>();

    this.channel.pipeline().addLast(new ErrorHandler());
  }

  public CompletableFuture<Void> getCloseFuture() {
    return closeFuture;
  }

  public CompletableFuture<Void> close() {
    this.channel.close();
    return closeFuture;
  }

  public CompletableFuture<SmtpClientResponse> startTls() {
    Preconditions.checkState(!isEncrypted(), "This connection is already using TLS");

    return send(new DefaultSmtpRequest(STARTTLS_COMMAND)).thenCompose(r -> {
      if (SmtpResponses.isError(r)) {
        return CompletableFuture.completedFuture(r);
      } else {
        return performTlsHandshake(r);
      }
    });
  }

  private CompletionStage<SmtpClientResponse> performTlsHandshake(SmtpClientResponse r) {
    CompletableFuture<SmtpClientResponse> ourFuture = new CompletableFuture<>();

    SslHandler sslHandler = new SslHandler(config.getSSLEngineSupplier().get());
    channel.pipeline().addFirst(sslHandler);

    sslHandler.handshakeFuture().addListener(nettyFuture -> {
      if (nettyFuture.isSuccess()) {
        ourFuture.complete(r);
      } else {
        ourFuture.completeExceptionally(nettyFuture.cause());
        close();
      }
    });

    return ourFuture;
  }

  public boolean isEncrypted() {
    return channel.pipeline().get(SslHandler.class) != null;
  }

  public CompletableFuture<SmtpClientResponse> send(SmtpRequest request) {
    Preconditions.checkNotNull(request);

    CompletableFuture<SmtpResponse[]> responseFuture = responseHandler.createResponseFuture(1, () -> createDebugString(request));
    channel.writeAndFlush(request);

    return applyOnExecutor(responseFuture, r -> new SmtpClientResponse(r[0], this));
  }

  public CompletableFuture<SmtpClientResponse> send(MessageContent content) {
    Preconditions.checkNotNull(content);

    CompletableFuture<SmtpResponse[]> responseFuture = responseHandler.createResponseFuture(1, () -> "message contents");

    writeContent(content);
    channel.flush();

    return applyOnExecutor(responseFuture, r -> new SmtpClientResponse(r[0], this));
  }

  public CompletableFuture<SmtpClientResponse[]> sendPipelined(SmtpRequest... requests) {
    Preconditions.checkNotNull(requests);

    return sendPipelined(null, requests);
  }

  public CompletableFuture<SmtpClientResponse[]> sendPipelined(MessageContent content, SmtpRequest... requests) {
    Preconditions.checkNotNull(requests);
    checkValidPipelinedRequest(requests);

    int expectedResponses = requests.length + (content == null ? 0 : 1);
    CompletableFuture<SmtpResponse[]> responseFuture = responseHandler.createResponseFuture(expectedResponses, () -> createDebugString(requests));

    if (content != null) {
      writeContent(content);
    }
    for (SmtpRequest r : requests) {
      channel.write(r);
    }

    channel.flush();

    return applyOnExecutor(responseFuture, rs -> {
      SmtpClientResponse[] smtpClientResponses = new SmtpClientResponse[rs.length];
      for (int i = 0; i < smtpClientResponses.length; i++) {
        smtpClientResponses[i] = new SmtpClientResponse(rs[i], this);
      }
      return smtpClientResponses;
    });
  }

  private void writeContent(MessageContent content) {
    if (isSupported(SupportedExtensions.EIGHT_BIT_MIME)) {
      channel.write(content.get8BitMimeEncodedContent());
    } else {
      channel.write(content.get7BitEncodedContent());
    }

    // SmtpRequestEncoder requires that we send an SmtpContent instance after the DATA command
    // to unset its contentExpected state.
    channel.write(EMPTY_LAST_CONTENT);
  }

  private static String createDebugString(SmtpRequest... requests) {
    return COMMA_JOINER.join(requests);
  }

  private static void checkValidPipelinedRequest(SmtpRequest[] requests) {
    Preconditions.checkArgument(requests.length > 0, "You must provide requests to pipeline");

    for (int i = 0; i < requests.length; i++) {
      SmtpCommand command = requests[i].command();
      boolean isLastRequest = (i == requests.length - 1);

      if (isLastRequest) {
        Preconditions.checkArgument(VALID_AT_END_PIPELINED_COMMANDS.contains(command),
            command.name() + " cannot be used in a pipelined request");
      } else {
        String errorMessage = VALID_AT_END_PIPELINED_COMMANDS.contains(command) ?
            " must appear last in a pipelined request" : " cannot be used in a pipelined request";

        Preconditions.checkArgument(VALID_ANYWHERE_PIPELINED_COMMANDS.contains(command),
            command.name() + errorMessage);
      }
    }
  }

  private <R, T> CompletableFuture<R> applyOnExecutor(CompletableFuture<T> eventLoopFuture, Function<T, R> mapper) {
    // use handleAsync to ensure exceptions and other callbacks are completed on the ExecutorService thread
    return eventLoopFuture.handleAsync((rs, e) -> {
      if (e != null) {
        throw Throwables.propagate(e);
      }

      return mapper.apply(rs);
    }, executorService);
  }

  public void setSupportedExtensions(EnumSet<SupportedExtensions> supportedExtensions) {
    this.supportedExtensions = supportedExtensions;
  }

  public boolean isSupported(SupportedExtensions ext) {
    return supportedExtensions.contains(ext);
  }

  private class ErrorHandler extends ChannelInboundHandlerAdapter {
    private Throwable cause;

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      this.cause = cause;
      ctx.close();
    }

    @Override
    @SuppressFBWarnings("NP_NONNULL_PARAM_VIOLATION") // https://github.com/findbugsproject/findbugs/issues/79
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      if (cause != null) {
        closeFuture.completeExceptionally(cause);
      } else {
        closeFuture.complete(null);
      }

      super.channelInactive(ctx);
    }
  }
}