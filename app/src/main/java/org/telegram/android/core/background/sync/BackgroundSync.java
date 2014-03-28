package org.telegram.android.core.background.sync;

import android.util.Log;
import android.os.Build;
import android.os.SystemClock;
import org.telegram.android.TelegramApplication;
import org.telegram.android.core.ApiUtils;
import org.telegram.android.core.model.*;
import org.telegram.android.core.model.update.TLLocalAffectedHistory;
import org.telegram.android.log.Logger;
import org.telegram.api.*;
import org.telegram.api.engine.TimeoutException;
import org.telegram.api.help.TLInviteText;
import org.telegram.api.messages.TLAffectedHistory;
import org.telegram.api.requests.*;
import org.telegram.mtproto.secure.Entropy;
import org.telegram.mtproto.time.TimeOverlord;
import org.telegram.tl.TLIntVector;
import org.telegram.tl.TLVector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

/**
 * Created by ex3ndr on 22.11.13.
 */
public class BackgroundSync extends BaseSync {

    private static final String SETTINGS_NAME = "org.telegram.android";

    private static final int ONLINE_TIMEOUT = 70000;
    private static final int TYPING_TIMEOUT = 8000;
    private static final int TYPING_SEND_TIMEOUT = 5000;

    private static final int SYNC_DC = 1;
    private static final int SYNC_DC_INTERVAL = HOUR;
    private static final int SYNC_ONLINE = 2;
    private static final int SYNC_ONLINE_INTERVAL = 60 * SEC;
    private static final int SYNC_PUSH = 3;
    private static final int SYNC_PUSH_INTERVAL = 6 * HOUR;
    private static final int SYNC_DELETIONS = 4;
    private static final int SYNC_TYPING = 5;
    private static final int SYNC_HISTORY = 6;
    private static final int SYNC_ACCEPTOR = 7;
    private static final int SYNC_INVITE = 8;
    private static final int SYNC_INVITE_INTERVAL = 24 * HOUR;
    private static final int SYNC_LOG = 9;
    private static final String TAG = "BackgroundSync";

    private long appSession = Entropy.generateRandomId() ^ 0x0F;

    private TelegramApplication application;

    private final ArrayList<TLInputAppEvent> logs = new ArrayList<TLInputAppEvent>();

    private final Object typingLock = new Object();
    private int typingPeerType;
    private int typingPeerId;
    private long typingTime;

    public BackgroundSync(TelegramApplication application) {
        super(application, SETTINGS_NAME);
        this.application = application;
        registerTechSync(SYNC_DC, "dcSync", SYNC_DC_INTERVAL);
        registerSync(SYNC_ONLINE, "onlineSync", SYNC_ONLINE_INTERVAL);
        registerSync(SYNC_PUSH, "pushSync", SYNC_PUSH_INTERVAL);
        registerSyncSingle(SYNC_DELETIONS, "deletionsSync");
        registerSyncEvent(SYNC_TYPING, "typingSync");
        registerSyncSingle(SYNC_HISTORY, "historyReadSync");
        registerSyncEvent(SYNC_ACCEPTOR, "encryptedAcceptor");
        registerSync(SYNC_INVITE, "inviteSync", SYNC_INVITE_INTERVAL);
        registerSyncEventAnon(SYNC_LOG, "sendLog");
    }

    public void sendLog(String type, String data) {
        double time = TimeOverlord.getInstance().getServerTime() / 1000.0;
        synchronized (logs) {
            logs.add(new TLInputAppEvent(time, type, appSession, data));
        }
        resetSync(SYNC_LOG);
    }

    public void resetInviteSync() {
        resetSync(SYNC_INVITE);
    }

    public void resetDcSync() {
        resetSync(SYNC_DC);
    }

    public void resetEncAcceptorSync() {
        resetSync(SYNC_ACCEPTOR);
    }

    public void resetHistorySync() {
        resetSync(SYNC_HISTORY);
    }

    public void resetTypingDelay() {
        typingTime = 0;
    }

    public void onTyping(int peerType, int peerId) {
        synchronized (typingLock) {
            if (peerId != typingPeerId || peerType != typingPeerType
                    || SystemClock.uptimeMillis() - typingTime > TYPING_SEND_TIMEOUT) {
                typingPeerType = peerType;
                typingPeerId = peerId;
                typingTime = SystemClock.uptimeMillis();
                resetSync(SYNC_TYPING);
            }
        }
    }

    public void onPushRegistered(String token) {
        String oldToken = this.preferences.getString("push_token", null);

        if (!token.equals(oldToken)) {
            this.preferences.edit().putString("push_token", token)
                    .putBoolean("push_registered", false).commit();
            resetSync(SYNC_PUSH);
        }
    }

    public void onAppVisibilityChanged() {
        resetSync(SYNC_ONLINE);
        resetSync(SYNC_ACCEPTOR);
    }

    public void resetDeletionsSync() {
        resetSync(SYNC_DELETIONS);
    }

    public void run() {
        init();
    }

    // Sync entities
    protected void dcSync() throws Exception {
        boolean synced = false;
        try {
            Log.e("Telegram DC", "getting dc config...");
            TLConfig config = application.getApi().doRpcCallNonAuth(new TLRequestHelpGetConfig());

            for(TLDcOption opt: config.getDcOptions())
                Log.e("Telegram DC", "get dc config "+opt.getId()+"."
                        +opt.getHostname()+"<"+opt.getIpAddress()+">:"
                        +opt.getPort());
            application.getApiStorage().updateSettings(config);
            application.getTechKernel().getSystemConfig().onConfig(config);
            application.getApi().resetConnectionInfo();
            synced = true;
        } catch (TimeoutException e) {
            int[] knownDcs = application.getApiStorage().getKnownDc();
            for (int i = 0; i < knownDcs.length; i++) {
                try {
                    TLConfig config = application.getApi().doRpcCallNonAuth(new TLRequestHelpGetConfig(), knownDcs[i]);

                    for(TLDcOption opt: config.getDcOptions())
                        Log.e("Telegram DC", "get dc config " + opt.getId() + "."
                                + opt.getHostname() + "<" + opt.getIpAddress() + ">:"
                                + opt.getPort());
                    application.getApiStorage().updateSettings(config);
                    application.getTechKernel().getSystemConfig().onConfig(config);
                    application.getApi().resetConnectionInfo();
                    synced = true;
                    break;
                } catch (TimeoutException e1) {

                }
            }
        }

        if (!synced) {
            throw new TimeoutException();
        }
    }

    protected void onlineSync() throws Exception {
        if (application.getUiKernel().isAppActive()) {
            application.getApi().doRpcCallWeak(new TLRequestAccountUpdateStatus(false), ONLINE_TIMEOUT);
        } else {
            application.getApi().doRpcCallWeak(new TLRequestAccountUpdateStatus(true), ONLINE_TIMEOUT);
        }
    }

    protected void pushSync() throws Exception {
        String token = this.preferences.getString("push_token", null);
        if (token != null) {
            application.getApi().doRpcCall(new TLRequestAccountRegisterDevice(
                    2,
                    token,
                    Build.MODEL,
                    Build.VERSION.RELEASE,
                    application.getTechKernel().getTechReflection().getAppVersion(),
                    false,
                    application.getTechKernel().getTechReflection().getAppLocale()));
        }
    }

    protected void deletionsSync() throws Exception {
        ChatMessage[] messages = application.getEngine().getMessagesEngine().getUnsyncedDeletedMessages();
        if (messages.length > 0) {
            TLIntVector mids = new TLIntVector();
            for (ChatMessage message : messages) {
                mids.add(message.getMid());
            }
            TLIntVector res = application.getApi().doRpcCall(new TLRequestMessagesDeleteMessages(mids));
            application.getEngine().onDeletedOnServer(res.toIntArray());
        }

        messages = application.getEngine().getMessagesEngine().getUnsyncedRestoredMessages();
        if (messages.length > 0) {
            TLIntVector mids = new TLIntVector();
            for (ChatMessage message : messages) {
                mids.add(message.getMid());
            }
            TLIntVector res = application.getApi().doRpcCall(new TLRequestMessagesRestoreMessages(mids));
            application.getEngine().onRestoredOnServer(res.toIntArray());
        }

        application.notifyUIUpdate();
    }

    protected void typingSync() {
        int peerType;
        int peerId;
        synchronized (typingLock) {
            peerType = typingPeerType;
            peerId = typingPeerId;
        }

        if (peerType == PeerType.PEER_USER) {
            User usr = application.getEngine().getUser(peerId);
            if (usr != null) {
                if (ApiUtils.getUserState(usr.getStatus()) != 0) {
                    Logger.d(TAG, "Ignoring typing: user offline");
                    return;
                }
            }

            application.getApi().doRpcCallWeak(new TLRequestMessagesSetTyping(new TLInputPeerContact(peerId), true), TYPING_TIMEOUT);
        } else if (peerType == PeerType.PEER_CHAT) {
            application.getApi().doRpcCallWeak(new TLRequestMessagesSetTyping(new TLInputPeerChat(peerId), true), TYPING_TIMEOUT);
        } else if (peerType == PeerType.PEER_USER_ENCRYPTED) {
            EncryptedChat chat = application.getEngine().getEncryptedChat(peerId);
            if (chat == null) {
                return;
            }

            User usr = application.getEngine().getUser(chat.getUserId());
            if (usr != null) {
                if (ApiUtils.getUserState(usr.getStatus()) != 0) {
                    Logger.d(TAG, "Ignoring typing: user offline");
                    return;
                }
            }

            application.getApi().doRpcCallWeak(new TLRequestMessagesSetEncryptedTyping(new TLInputEncryptedChat(chat.getId(), chat.getAccessHash()), true), TYPING_TIMEOUT);
        }
    }

    protected void historyReadSync() throws Exception {
        while (true) {
            DialogDescription[] descriptionList = application.getEngine().getUnreadedRemotelyDescriptions();
            if (descriptionList.length == 0) {
                return;
            }
            for (DialogDescription description : descriptionList) {
                if (description.getPeerType() == PeerType.PEER_USER) {
                    int mid = description.getLastLocalViewedMessage();
                    User user = application.getEngine().getUser(description.getPeerId());
                    TLInputPeerForeign peer = new TLInputPeerForeign(user.getUid(), user.getAccessHash());
                    TLAffectedHistory history = application.getApi().doRpcCall(new TLRequestMessagesReadHistory(peer, mid, 0));
                    application.getEngine().getDialogsEngine().onMaxRemoteViewed(description.getPeerType(), description.getPeerId(), mid);
                    application.getUpdateProcessor().onMessage(new TLLocalAffectedHistory(history));
                } else if (description.getPeerType() == PeerType.PEER_CHAT) {
                    int mid = description.getLastLocalViewedMessage();
                    TLAffectedHistory history = application.getApi().doRpcCall(new TLRequestMessagesReadHistory(new TLInputPeerChat(description.getPeerId()), mid, 0));
                    application.getEngine().getDialogsEngine().onMaxRemoteViewed(description.getPeerType(), description.getPeerId(), mid);
                    application.getUpdateProcessor().onMessage(new TLLocalAffectedHistory(history));
                } else if (description.getPeerType() == PeerType.PEER_USER_ENCRYPTED) {
                    EncryptedChat chat = application.getEngine().getEncryptedChat(description.getPeerId());
                    if (chat == null) {
                        return;
                    }
                    ChatMessage[] messages = application.getEngine().getMessagesEngine().findUnreadedSelfDestructMessages(description.getPeerType(), description.getPeerId());
                    for (ChatMessage message : messages) {
                        message.setMessageDieTime((int) (System.currentTimeMillis() / 1000 + message.getMessageTimeout()));
                        application.getSelfDestructProcessor().performSelfDestruct(message.getDatabaseId(), message.getMessageDieTime());
                        application.getEngine().getMessagesEngine().update(message);
                        application.getDataSourceKernel().onSourceUpdateMessage(message);
                        application.notifyUIUpdate();
                    }
                    int mid = description.getLastLocalViewedMessage();
                    application.getApi().doRpcCall(new TLRequestMessagesReadEncryptedHistory(new TLInputEncryptedChat(description.getPeerId(), chat.getAccessHash()), mid));
                    application.getEngine().getDialogsEngine().onMaxRemoteViewed(description.getPeerType(), description.getPeerId(), mid);
                }
            }
        }
    }

    protected void encryptedAcceptor() throws Exception {
        if (!application.getUiKernel().isAppActive()) {
            return;
        }
        EncryptedChat[] chats = application.getEngine().getSecretEngine().getPendingEncryptedChats();
        for (EncryptedChat chat : chats) {
            application.getEncryptionController().confirmEncryption(chat.getId());
        }
    }

    protected void inviteSync() throws Exception {
        String locale = Locale.getDefault().getLanguage();
        TLInviteText text = application.getApi().doRpcCall(new TLRequestHelpGetInviteText(locale));
        application.getTechKernel().getSystemConfig().onInviteMessage(text.getMessage(), locale);
    }

    protected void sendLog() throws Exception {
        TLInputAppEvent[] appEvents = null;
        synchronized (logs) {
            appEvents = logs.toArray(new TLInputAppEvent[0]);
            logs.clear();
        }
        if (appEvents.length > 0) {
            TLVector<TLInputAppEvent> events = new TLVector<TLInputAppEvent>();
            Collections.addAll(events, appEvents);
            application.getApi().doRpcCallNonAuth(new TLRequestHelpSaveAppLog(events));
        }
    }
}
