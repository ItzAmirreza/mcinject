package io.netty.channel;

import java.util.function.Function;

/**
 * The in-pipeline tap.
 *
 * <p>This class lives in {@code io.netty.channel} on purpose: the agent defines it directly into the
 * game's Netty classloader (Fabric's KnotClassLoader), which is the only loader that can see
 * {@link ChannelDuplexHandler}. It talks back to the agent through a {@link Function} — a
 * {@code java.base} type both classloaders agree on — so no other class has to be shared across the
 * loader boundary.
 *
 * <p>The callback receives {@code {tag, message, ChannelHandlerContext}} and returns the message to
 * forward: the same object to pass it through, a different object to rewrite it, or {@code null} to
 * drop it. Anything thrown by the callback is swallowed and the packet passes through untouched —
 * a bug in the caller must never disconnect the player.
 */
public class McInjectTap extends ChannelDuplexHandler {

    public static final String NAME = "mcinject_tap";

    private final Function<Object[], Object> callback;

    public McInjectTap(Function<Object[], Object> callback) {
        this.callback = callback;
    }

    private Object fire(String tag, Object msg, ChannelHandlerContext ctx) {
        try {
            return callback.apply(new Object[]{tag, msg, ctx});
        } catch (Throwable t) {
            return msg;
        }
    }

    private void notifyEvent(String tag, ChannelHandlerContext ctx) {
        try {
            callback.apply(new Object[]{tag, null, ctx});
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        Object out = fire("in", msg, ctx);
        if (out != null) {
            super.channelRead(ctx, out);
        } else {
            release(msg);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        Object out = fire("out", msg, ctx);
        if (out != null) {
            super.write(ctx, out, promise);
        } else {
            release(msg);
            promise.setSuccess();
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        notifyEvent("added", ctx);
        super.handlerAdded(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        notifyEvent("removed", ctx);
        super.handlerRemoved(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        notifyEvent("inactive", ctx);
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        notifyEvent("exception", ctx);
        super.exceptionCaught(ctx, cause);
    }

    /** Dropped messages still own pooled buffers; leaking them would starve the game's allocator. */
    private static void release(Object msg) {
        try {
            if (msg instanceof io.netty.util.ReferenceCounted rc && rc.refCnt() > 0) {
                rc.release();
            }
        } catch (Throwable ignored) {
        }
    }
}
