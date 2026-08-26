package top.lxvb.yuque;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public final class MessageStateTest {
    @Test public void pendingMessageTransitionsThroughFailureAndRead() {
        Models.User sender = new Models.User("user-1", "sender", "发送者", "", false);
        Models.Message pending = Models.Message.pending(
                "conversation-1", sender, "client-1", "text", "hello", "", "");
        assertEquals(Models.Message.SENDING, pending.deliveryState);
        assertFalse(pending.readByPeer);
        assertEquals(Models.Message.FAILED, pending.withDeliveryState(Models.Message.FAILED).deliveryState);
        assertTrue(pending.withDeliveryState(Models.Message.SENT).asRead().readByPeer);
    }

    @Test public void mediaProgressIsBoundedAndReadyForSend() {
        Models.User sender = new Models.User("user-1", "sender", "发送者", "", false);
        Models.Message media = Models.Message.uploading(
                "conversation-1", sender, "media-1", "image", "content://image/1", "image/jpeg");
        assertEquals(Models.Message.UPLOADING, media.deliveryState);
        assertEquals(100, media.withProgress(120).progress);
        Models.Message ready = media.readyToSend("/uploads/image.jpg", "image/jpeg", "image");
        assertEquals(Models.Message.SENDING, ready.deliveryState);
        assertEquals("/uploads/image.jpg", ready.mediaUrl);
    }
}
