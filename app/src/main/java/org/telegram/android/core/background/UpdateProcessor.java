package org.telegram.android.core.background;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import org.telegram.android.TelegramApplication;
import org.telegram.android.core.ApiUtils;
import org.telegram.android.core.EngineUtils;
import org.telegram.android.core.model.*;
import org.telegram.android.core.model.media.TLAbsLocalAvatarPhoto;
import org.telegram.android.core.model.media.TLLocalDocument;
import org.telegram.android.core.model.media.TLLocalPhoto;
import org.telegram.android.core.model.service.TLLocalActionUserRegistered;
import org.telegram.android.core.model.update.*;
import org.telegram.android.log.Logger;
import org.telegram.android.reflection.CrashHandler;
import org.telegram.api.*;
import org.telegram.api.engine.RpcException;
import org.telegram.api.messages.TLAbsSentMessage;
import org.telegram.api.messages.TLAbsStatedMessage;
import org.telegram.api.messages.TLAbsStatedMessages;
import org.telegram.api.requests.TLRequestUpdatesGetDifference;
import org.telegram.api.requests.TLRequestUpdatesGetState;
import org.telegram.api.updates.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Author: Korshakov Stepan
 * Created: 29.07.13 2:27
 */
public class UpdateProcessor {

    private static final String TAG = "Updater";

    private class PackageIdentity {
        public int seq;
        public int seqEnd;
        public int date;
        public int pts;
        public int qts;
    }

    private static final int CHECK_TIMEOUT = 2000;

    private static final int DIFF_TIMEOUT = 60000;

    private TelegramApplication application;
    private Thread corrector;
    private Looper correctorLooper;
    private Handler correctorHandler;

    private boolean isInvalidated;
    private boolean isBrokenSequence = false;

    private UpdateState updateState;

    private HashMap<Integer, Object> further = new HashMap<Integer, Object>();

    private boolean isDestroyed = false;
    private boolean isStarted = false;


    public UpdateProcessor(TelegramApplication _application) {
        this.application = _application;
        this.isInvalidated = false;
        this.updateState = new UpdateState(_application);

        corrector = new Thread() {
            @Override
            public void run() {
                Looper.prepare();
                correctorLooper = Looper.myLooper();
                correctorHandler = new Handler() {
                    @Override
                    public void handleMessage(Message msg) {
                        if (isDestroyed) {
                            return;
                        }
                        if (!application.isLoggedIn())
                            return;
                        if (msg.what == 0) {
                            if (!isInvalidated)
                                return;

                            application.getMonitor().waitForConnection();

                            if (!hasState()) {
                                Logger.d(TAG, "Retreiving fresh state");
                                try {
                                    TLState state = application.getApi().doRpcCall(new TLRequestUpdatesGetState(), DIFF_TIMEOUT);
                                    if (isDestroyed) {
                                        return;
                                    }
                                    if (!application.isLoggedIn())
                                        return;

                                    updateState.setFullState(
                                            state.getPts(),
                                            state.getSeq(),
                                            state.getDate(),
                                            state.getQts());

                                    dumpState();

                                    isInvalidated = false;
                                    onValidated();
                                    return;
                                } catch (RpcException e) {
                                    Logger.t(TAG, e);
                                    CrashHandler.logHandledException(e);
                                } catch (IOException e) {
                                    Logger.t(TAG, e);
                                }
                            } else {
                                Logger.d(TAG, "Getting difference");
                                try {
                                    Log.e(TAG, "rpc -> TLRequestUpdatesGetDifference"
                                            +" state: pts="+updateState.getPts()
                                            +" qts="+updateState.getQts()
                                            +" date="+updateState.getDate()
                                    );
                                    TLAbsDifference diff = application.getApi().doRpcCall(new TLRequestUpdatesGetDifference(updateState.getPts(), updateState.getDate(), updateState.getQts()), DIFF_TIMEOUT);
                                    if (isDestroyed) {
                                        return;
                                    }
                                    if (!application.isLoggedIn())
                                        return;
                                    Log.e(TAG, "rpc <- receive difference " + diff.getClass().getSimpleName());
                                    if (diff instanceof TLDifference) {
                                        TLDifference difference = (TLDifference) diff;
                                        TLState newSt = difference.getState();
                                        Log.e(TAG, "rpc <- difference"
                                                + " msg=" + difference.getNewMessages().size()
                                                + " chat=" + difference.getChats().size()
                                                + " update=" + difference.getOtherUpdates().size()
                                                + " user=" + difference.getUsers().size()
                                                + " state: pts=" + updateState.getPts() + "->" + newSt.getPts()
                                                + " qts=" + updateState.getQts() + "->" + newSt.getQts()
                                                + " seq=" + updateState.getSeq() + "->" + newSt.getSeq()
                                                + " date=" + updateState.getDate() + "->" + newSt.getDate()
                                        );
                                        onDifference(difference);
                                        onValidated();
                                    } else if (diff instanceof TLDifferenceSlice) {
                                        TLDifferenceSlice slice = (TLDifferenceSlice) diff;
                                        TLState newSt = slice.getIntermediateState();
                                        Log.e(TAG, "rpc <- difference"
                                                + " msg=" + slice.getNewMessages().size()
                                                + " chat=" + slice.getChats().size()
                                                + " update=" + slice.getOtherUpdates().size()
                                                + " user=" + slice.getUsers().size()
                                                + " state: pts=" + updateState.getPts() + "->" + newSt.getPts()
                                                + " qts=" + updateState.getQts() + "->" + newSt.getQts()
                                                + " seq=" + updateState.getSeq() + "->" + newSt.getSeq()
                                                + " date=" + updateState.getDate() + "->" + newSt.getDate()
                                        );
                                        onSliceDifference(slice);
                                        getHandler().sendEmptyMessage(0);
                                    } else if (diff instanceof TLDifferenceEmpty) {
                                        TLDifferenceEmpty empty = (TLDifferenceEmpty) diff;
                                        updateState.setSeq(empty.getSeq());
                                        updateState.setDate(empty.getDate());
                                        dumpState();
                                        isInvalidated = false;
                                        onValidated();
                                    }
                                    return;
                                } catch (RpcException e) {
                                    Logger.t(TAG, e);
                                    CrashHandler.logHandledException(e);
                                } catch (IOException e) {
                                    Logger.t(TAG, e);
                                }
                            }

                            // TODO: correct back-off
                            getHandler().sendEmptyMessageDelayed(0, 1000);
                        } else if (msg.what == 1) {
                            checkBrokenSequence();
                        }
                    }
                };
                Looper.loop();
            }
        };
        corrector.setName("CorrectorThread#" + corrector.hashCode());
        Logger.d(TAG, "Initied");
    }

    public void runUpdateProcessor() {
        if (isStarted) {
            return;
        }
        isStarted = true;
        corrector.start();
        if (isInvalidated) {
            while (correctorHandler == null) {
                Thread.yield();
            }
            correctorHandler.removeMessages(0);
            correctorHandler.sendEmptyMessage(0);
        }
    }

    private Handler getHandler() {
        while (correctorHandler == null) {
            Thread.yield();
        }
        return correctorHandler;
    }

    public synchronized void invalidateUpdates() {
        application.getKernel().getLifeKernel().onUpdateRequired();
        if (!isStarted) {
            isInvalidated = true;
        } else {
            if (isInvalidated) {
                Logger.w(TAG, "Trying to invalidate already invialidated: " + updateState.getSeq());
            } else {
                Logger.w(TAG, "Invialidated: " + updateState.getSeq());
                isInvalidated = true;
                getHandler().removeMessages(0);
                getHandler().sendEmptyMessage(0);
            }
        }
    }

    private boolean hasState() {
        return updateState.getPts() > 0;
    }

    private PackageIdentity getPackageIdentity(Object object) {
        if (object instanceof TLLocalCreateChat) {
            TLAbsStatedMessage statedMessage = ((TLLocalCreateChat) object).getMessage();
            PackageIdentity identity = new PackageIdentity();
            identity.seq = statedMessage.getSeq();
            identity.seqEnd = 0;
            identity.pts = statedMessage.getPts();
            identity.date = 0;
            return identity;
        }
        if (object instanceof TLLocalUpdateChatPhoto) {
            TLAbsStatedMessage statedMessage = ((TLLocalUpdateChatPhoto) object).getMessage();
            PackageIdentity identity = new PackageIdentity();
            identity.seq = statedMessage.getSeq();
            identity.seqEnd = 0;
            identity.pts = statedMessage.getPts();
            identity.date = 0;
            return identity;
        }
        if (object instanceof TLLocalEditChatTitle) {
            TLAbsStatedMessage statedMessage = ((TLLocalEditChatTitle) object).getMessage();
            PackageIdentity identity = new PackageIdentity();
            identity.seq = statedMessage.getSeq();
            identity.seqEnd = 0;
            identity.pts = statedMessage.getPts();
            identity.date = 0;
            return identity;
        }
        if (object instanceof TLLocalRemoveChatUser) {
            TLAbsStatedMessage statedMessage = ((TLLocalRemoveChatUser) object).getMessage();
            PackageIdentity identity = new PackageIdentity();
            identity.seq = statedMessage.getSeq();
            identity.seqEnd = 0;
            identity.pts = statedMessage.getPts();
            identity.date = 0;
            return identity;
        }
        if (object instanceof TLLocalAddChatUser) {
            TLAbsStatedMessage statedMessage = ((TLLocalAddChatUser) object).getMessage();
            PackageIdentity identity = new PackageIdentity();
            identity.seq = statedMessage.getSeq();
            identity.seqEnd = 0;
            identity.pts = statedMessage.getPts();
            identity.date = 0;
            return identity;
        }

        if (object instanceof TLLocalMessageEncryptedSent) {
            TLLocalMessageEncryptedSent encryptedSent = (TLLocalMessageEncryptedSent) object;
            PackageIdentity identity = new PackageIdentity();
            identity.seq = 0;
            identity.seqEnd = 0;
            identity.pts = 0;
            identity.date = encryptedSent.getEncryptedMessage().getDate();
            return identity;
        } else if (object instanceof TLLocalMessageSent) {
            TLAbsSentMessage absSentMessage = ((TLLocalMessageSent) object).getAbsSentMessage();
            PackageIdentity identity = new PackageIdentity();
            identity.seq = absSentMessage.getSeq();
            identity.seqEnd = 0;
            identity.pts = absSentMessage.getPts();
            identity.date = absSentMessage.getDate();
            return identity;
        } else if (object instanceof TLLocalMessageSentStated) {
            TLAbsStatedMessage statedMessage = ((TLLocalMessageSentStated) object).getAbsStatedMessage();
            PackageIdentity identity = new PackageIdentity();
            identity.seq = statedMessage.getSeq();
            identity.seqEnd = 0;
            identity.pts = statedMessage.getPts();
            identity.date = 0;
            return identity;
        } else if (object instanceof TLLocalMessageSentPhoto) {
            TLAbsStatedMessage statedMessage = ((TLLocalMessageSentPhoto) object).getAbsStatedMessage();
            PackageIdentity identity = new PackageIdentity();
            identity.seq = statedMessage.getSeq();
            identity.seqEnd = 0;
            identity.pts = statedMessage.getPts();
            identity.date = 0;
            return identity;
        } else if (object instanceof TLLocalMessageSentDoc) {
            TLAbsStatedMessage statedMessage = ((TLLocalMessageSentDoc) object).getAbsStatedMessage();
            PackageIdentity identity = new PackageIdentity();
            identity.seq = statedMessage.getSeq();
            identity.seqEnd = 0;
            identity.pts = statedMessage.getPts();
            identity.date = 0;
            return identity;
        } else if (object instanceof TLLocalMessagesSentStated) {
            TLAbsStatedMessages statedMessages = ((TLLocalMessagesSentStated) object).getAbsStatedMessages();
            PackageIdentity identity = new PackageIdentity();
            identity.seq = statedMessages.getSeq();
            identity.seqEnd = 0;
            identity.pts = statedMessages.getPts();
            identity.date = 0;
            return identity;
        } else if (object instanceof TLLocalAffectedHistory) {
            PackageIdentity identity = new PackageIdentity();
            identity.seq = ((TLLocalAffectedHistory) object).getAffectedHistory().getSeq();
            identity.seqEnd = 0;
            identity.pts = ((TLLocalAffectedHistory) object).getAffectedHistory().getPts();
            identity.date = 0;
            return identity;
        } else if (object instanceof TLUpdateShortChatMessage) {
            PackageIdentity identity = new PackageIdentity();
            identity.seq = ((TLUpdateShortChatMessage) object).getSeq();
            identity.seqEnd = 0;
            identity.pts = ((TLUpdateShortChatMessage) object).getPts();
            identity.date = ((TLUpdateShortChatMessage) object).getDate();
            return identity;
        } else if (object instanceof TLUpdateShortMessage) {
            PackageIdentity identity = new PackageIdentity();
            identity.seq = ((TLUpdateShortMessage) object).getSeq();
            identity.seqEnd = 0;
            identity.pts = ((TLUpdateShortMessage) object).getPts();
            identity.date = ((TLUpdateShortMessage) object).getDate();
            return identity;
        } else if (object instanceof TLUpdateShort) {
            PackageIdentity identity = new PackageIdentity();
            identity.seq = 0;
            identity.seqEnd = 0;
            identity.pts = 0;
            identity.date = ((TLUpdateShort) object).getDate();
            if (((TLUpdateShort) object).getUpdate() instanceof TLUpdateNewEncryptedMessage) {
                identity.qts = ((TLUpdateNewEncryptedMessage) (((TLUpdateShort) object).getUpdate())).getQts();
            }
            return identity;
        } else if (object instanceof TLUpdatesTooLong) {
            PackageIdentity identity = new PackageIdentity();
            identity.seq = 0;
            identity.seqEnd = 0;
            identity.pts = 0;
            identity.date = 0;
            return identity;
        } else if (object instanceof TLUpdates) {
            PackageIdentity identity = new PackageIdentity();
            identity.seq = ((TLUpdates) object).getSeq();
            identity.seqEnd = 0;
            identity.pts = 0;
            identity.date = ((TLUpdates) object).getDate();
            identity.qts = 0;
            for (TLAbsUpdate update : (((TLUpdates) object).getUpdates())) {
                if (update instanceof TLUpdateNewEncryptedMessage) {
                    identity.qts = Math.max(identity.qts, ((TLUpdateNewEncryptedMessage) update).getQts());
                }
            }
            return identity;
        } else if (object instanceof TLUpdatesCombined) {
            PackageIdentity identity = new PackageIdentity();
            identity.seq = ((TLUpdatesCombined) object).getSeqStart();
            identity.seqEnd = ((TLUpdatesCombined) object).getSeq();
            identity.pts = 0;
            identity.date = ((TLUpdatesCombined) object).getDate();
            for (TLAbsUpdate update : (((TLUpdatesCombined) object).getUpdates())) {
                if (update instanceof TLUpdateNewEncryptedMessage) {
                    identity.qts = Math.max(identity.qts, ((TLUpdateNewEncryptedMessage) update).getQts());
                }
            }
            return identity;
        }

        return null;
    }

    public synchronized void checkBrokenSequence() {
        if (!isStarted)
            return;
        if (!isBrokenSequence)
            return;
        getHandler().removeMessages(1);
        isBrokenSequence = false;
        Logger.d(TAG, "#### | Sequence dies by timeout");
        invalidateUpdates();
    }

    private void requestSequenceCheck() {
        if (!isStarted)
            return;
        getHandler().removeMessages(1);
        getHandler().sendEmptyMessageDelayed(1, CHECK_TIMEOUT);
    }

    private void cancelSequenceCheck() {
        if (!isStarted)
            return;
        getHandler().removeMessages(1);
    }

    public synchronized boolean accept(PackageIdentity identity) {
        if (identity == null)
            return true;
        if (!hasState()) {
            return true;
        }

        if (identity.seq != 0) {
            if (identity.seq <= updateState.getSeq()) {
                return false;
            } else {
                return true;
            }
        }
        return true;
    }

    public synchronized boolean causeSeqInvalidation(PackageIdentity identity) {
        if (!hasState()) {
            return false;
        }

        if (identity.seq != 0) {
            if (identity.seq != updateState.getSeq() + 1) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean causeInvalidation(Object object) {
        if (object instanceof TLUpdateShortChatMessage) {
            TLUpdateShortChatMessage chatMessage = (TLUpdateShortChatMessage) object;
            if (application.getEngine().getUser(chatMessage.getFromId()) == null)
                return true;
            if (application.getEngine().getDescriptionForPeer(PeerType.PEER_CHAT, chatMessage.getChatId()) == null)
                return true;
        } else if (object instanceof TLUpdateShortMessage) {
            TLUpdateShortMessage chatMessage = (TLUpdateShortMessage) object;
            if (application.getEngine().getUser(chatMessage.getFromId()) == null)
                return true;
        }

        return false;
    }

    private synchronized void onValidated() {
        if (further.size() > 0) {
            Integer[] keys = further.keySet().toArray(new Integer[0]);
            int[] keys2 = new int[keys.length];
            for (int i = 0; i < keys2.length; i++) {
                keys2[i] = keys[i];
            }
            Arrays.sort(keys2);
            Object[] messages = new Object[keys2.length];
            for (int i = 0; i < keys2.length; i++) {
                messages[i] = further.get(keys2[i]);
            }

            further.clear();
            for (Object object : messages) {
                onMessage(object);
            }
        }
        application.getKernel().getLifeKernel().onUpdateReceived();
    }

    public synchronized void onMessage(TLAbsUpdates updates) {
        onMessage((Object) updates);
    }

    public synchronized void onMessage(TLLocalUpdate localUpdate) {
        onMessage((Object) localUpdate);
    }

    private synchronized void onMessage(Object object) {
        if (isDestroyed) {
            return;
        }
        if (!isStarted) {
            return;
        }

        PackageIdentity identity = getPackageIdentity(object);
        if (identity != null && identity.seq > 0) {
            Logger.d(TAG, identity.seq + " : Message " + object);
        } else {
            Logger.d(TAG, "#### | Message " + object);
        }

        if (!accept(identity)) {
            return;
        }

        if (isInvalidated) {
            further.put(identity.seq, object);
            getHandler().removeMessages(0);
            getHandler().sendEmptyMessage(0);
            return;
        }

        if (causeSeqInvalidation(identity)) {
            Logger.d(TAG, identity.seq + " | Out of sequence");
            further.put(identity.seq, object);
            if (!isBrokenSequence) {
                isBrokenSequence = true;
                requestSequenceCheck();
            }
            return;
        }

        if (isBrokenSequence && identity.seq != 0) {
            isBrokenSequence = false;
            Logger.d(TAG, identity.seq + " | Sequence fixed");
            cancelSequenceCheck();
        }

        if (causeInvalidation(object)) {
            further.put(identity.seq, object);
            invalidateUpdates();
            return;
        }

        if (object instanceof TLUpdateShortChatMessage) {
            onUpdateShortChatMessage((TLUpdateShortChatMessage) object);
        } else if (object instanceof TLUpdateShortMessage) {
            onUpdateShortMessage((TLUpdateShortMessage) object);
        } else if (object instanceof TLUpdateShort) {
            onUpdateShort((TLUpdateShort) object, identity);
        } else if (object instanceof TLUpdates) {
            onUpdates((TLUpdates) object, identity);
        } else if (object instanceof TLUpdatesCombined) {
            onCombined((TLUpdatesCombined) object, identity);
        } else if (object instanceof TLUpdatesTooLong) {
            invalidateUpdates();
        } else if (object instanceof TLLocalUpdate) {
            onLocalUpdate((TLLocalUpdate) object);
        }

        if (identity != null && identity.seq != 0) {
            if (identity.seqEnd != 0) {
                updateState.setSeq(identity.seqEnd);
            } else {
                updateState.setSeq(identity.seq);
            }

            if (identity.date != 0) {
                updateState.setDate(identity.date);
            }

            if (identity.qts != 0) {
                updateState.setQts(identity.qts);
            }

            if (identity.pts != 0) {
                updateState.setPts(identity.pts);
            }

            dumpState();
        }

        onValidated();

        application.notifyUIUpdate();
    }

    public void onSyncSeqArrived(int seq) {
        if (updateState.getSeq() < seq) {
            Logger.d(TAG, "Push sync causes invalidation");
            invalidateUpdates();
        }
    }

    private void onLocalUpdate(TLLocalUpdate update) {
        if (update instanceof TLLocalMessageSent) {
            application.getEngine().onMessageSent(
                    ((TLLocalMessageSent) update).getMessage(),
                    ((TLLocalMessageSent) update).getAbsSentMessage());
        } else if (update instanceof TLLocalMessageSentStated) {
            application.getEngine().onMessageSent(
                    ((TLLocalMessageSentStated) update).getMessage(),
                    ((TLLocalMessageSentStated) update).getAbsStatedMessage());
        } else if (update instanceof TLLocalMessagesSentStated) {
            application.getEngine().onForwarded(
                    ((TLLocalMessagesSentStated) update).getMessage(),
                    ((TLLocalMessagesSentStated) update).getAbsStatedMessages());
        } else if (update instanceof TLLocalMessageEncryptedSent) {
            application.getEngine().onMessageSent(
                    ((TLLocalMessageEncryptedSent) update).getMessage(),
                    ((TLLocalMessageEncryptedSent) update).getEncryptedMessage().getDate());
        } else if (update instanceof TLLocalMessageSentPhoto) {
            TLLocalMessageSentPhoto sentPhoto = (TLLocalMessageSentPhoto) update;
            TLMessage message = (TLMessage) sentPhoto.getAbsStatedMessage().getMessage();
            TLLocalPhoto object = (TLLocalPhoto) EngineUtils.convertMedia(message.getMedia());
            if (object.getFastPreview().length == 0) {
                object.setFastPreview(sentPhoto.getFastPreview());
                object.setFastPreviewW(sentPhoto.getFastPreviewW());
                object.setFastPreviewH(sentPhoto.getFastPreviewH());

                TLAbsPhotoSize size = ApiUtils.findSmallest((TLPhoto) ((TLMessageMediaPhoto) message.getMedia()).getPhoto());
                if (size instanceof TLPhotoSize) {
                    object.setFastPreviewKey(((TLPhotoSize) size).getLocation().getLocalId() + "." + ((TLPhotoSize) size).getLocation().getVolumeId());
                } else if (size instanceof TLPhotoCachedSize) {
                    object.setFastPreviewKey(((TLPhotoCachedSize) size).getLocation().getLocalId() + "." + ((TLPhotoCachedSize) size).getLocation().getVolumeId());
                }
            }
            application.getEngine().onMessagePhotoSent(sentPhoto.getMessage(), message.getDate(), message.getId(), object);
        } else if (update instanceof TLLocalMessageSentDoc) {
            TLLocalMessageSentDoc sentDoc = (TLLocalMessageSentDoc) update;
            TLMessage message = (TLMessage) sentDoc.getAbsStatedMessage().getMessage();
            TLLocalDocument doc = (TLLocalDocument) EngineUtils.convertMedia(message.getMedia());
            if (doc.getFastPreview().length == 0) {
                if (sentDoc.getFastPreview().length > 0) {
                    doc.setFastPreview(sentDoc.getFastPreview());
                    doc.setPreviewW(sentDoc.getFastPreviewW());
                    doc.setPreviewH(sentDoc.getFastPreviewH());
                }
            }
            application.getEngine().onMessageDocSent(sentDoc.getMessage(), message.getDate(), message.getId(), doc);
        }
    }

    private void onUpdates(TLUpdates updates, PackageIdentity identity) {
        application.getEngine().onUsers(updates.getUsers());
        application.getEngine().getGroupsEngine().onGroupsUpdated(updates.getChats());
        for (TLAbsUpdate u : updates.getUpdates()) {
            Log.e(TAG, "rpc <- updates "+u.getClass().getSimpleName());
            onUpdate(updates.getDate(), u, identity);
        }
    }

    private void onUpdateShort(TLUpdateShort updateShort, PackageIdentity identity) {
        Log.e(TAG, "rpc <- update short "+updateShort.getUpdate().getClass().getSimpleName());
        onUpdate(updateShort.getDate(), updateShort.getUpdate(), identity);
    }

    private void onUpdate(int date, TLAbsUpdate update, PackageIdentity identity) {
        Logger.d(TAG, "#### | Update: " + update);

        if (update instanceof TLUpdateUserTyping) {
            TLUpdateUserTyping userTyping = ((TLUpdateUserTyping) update);
            application.getTypingStates().onUserTyping(userTyping.getUserId(), date);
        } else if (update instanceof TLUpdateChatUserTyping) {
            TLUpdateChatUserTyping userTyping = (TLUpdateChatUserTyping) update;
            application.getTypingStates().onUserChatTyping(userTyping.getUserId(), userTyping.getChatId(), date);
        } else if (update instanceof TLUpdateUserStatus) {
            TLUpdateUserStatus updateUserStatus = (TLUpdateUserStatus) update;
//            if (!EngineUtils.hasUser(users, updateUserStatus.getUserId())) {
//                application.getEngine().getUsersEngine().onUserStatus(updateUserStatus.getUserId(), updateUserStatus.getStatus());
//            }
            application.getEngine().getUsersEngine().onUserStatus(updateUserStatus.getUserId(), updateUserStatus.getStatus());
        } else if (update instanceof TLUpdateUserName) {
            TLUpdateUserName updateUserName = (TLUpdateUserName) update;
//            if (!EngineUtils.hasUser(users, updateUserName.getUserId())) {
//                application.getEngine().getUsersEngine().onUserNameChanges(updateUserName.getUserId(), updateUserName.getFirstName(), updateUserName.getLastName());
//            }
            application.getEngine().getUsersEngine().onUserNameChanges(updateUserName.getUserId(), updateUserName.getFirstName(), updateUserName.getLastName());
        } else if (update instanceof TLUpdateUserPhoto) {
            TLUpdateUserPhoto updateUserPhoto = (TLUpdateUserPhoto) update;
//            if (!EngineUtils.hasUser(users, updateUserPhoto.getUserId())) {
//                TLAbsLocalAvatarPhoto photo = EngineUtils.convertAvatarPhoto(updateUserPhoto.getPhoto());
//                application.getEngine().getUsersEngine().onUserPhotoChanges(updateUserPhoto.getUserId(), photo);
//            }
            TLAbsLocalAvatarPhoto photo = EngineUtils.convertAvatarPhoto(updateUserPhoto.getPhoto());
            application.getEngine().getUsersEngine().onUserPhotoChanges(updateUserPhoto.getUserId(), photo);
        } else if (update instanceof TLUpdateContactRegistered) {
            User src = application.getEngine().getUser(((TLUpdateContactRegistered) update).getUserId());
            application.getEngine().onNewInternalServiceMessage(
                    PeerType.PEER_USER,
                    src.getUid(),
                    src.getUid(),
                    ((TLUpdateContactRegistered) update).getDate(),
                    new TLLocalActionUserRegistered());
            application.getNotifications().onNewMessageJoined(src.getDisplayName(), src.getUid(), 0, src.getPhoto());
            application.getSyncKernel().getContactsSync().invalidateContactsSync();
        } else if (update instanceof TLUpdateContactLink) {
            TLUpdateContactLink link = (TLUpdateContactLink) update;
//            if (!EngineUtils.hasUser(users, link.getUserId())) {
//                // TODO: Implement link update
//            }
        } else if (update instanceof TLUpdateChatParticipants) {
            TLUpdateChatParticipants participants = (TLUpdateChatParticipants) update;
            application.getEngine().getFullGroupEngine().onChatParticipants(participants.getParticipants());
        } else if (update instanceof TLUpdateChatParticipantAdd) {
            TLUpdateChatParticipantAdd addUser = (TLUpdateChatParticipantAdd) update;
            application.getEngine().getFullGroupEngine().onChatUserAdded(addUser.getChatId(), addUser.getUserId(), addUser.getInviterId(), date);
        } else if (update instanceof TLUpdateChatParticipantDelete) {
            TLUpdateChatParticipantDelete deleteUser = (TLUpdateChatParticipantDelete) update;
            application.getEngine().getFullGroupEngine().onChatUserRemoved(deleteUser.getChatId(), deleteUser.getUserId());
        } else if (update instanceof TLUpdateReadMessages) {
            TLUpdateReadMessages readMessages = (TLUpdateReadMessages) update;
            application.getEngine().onMessagesReaded(readMessages.getMessages().toIntArray());
        } else if (update instanceof TLUpdateDeleteMessages) {
            TLUpdateDeleteMessages deleteMessages = (TLUpdateDeleteMessages) update;
            application.getEngine().onDeletedOnServer(deleteMessages.getMessages().toIntArray());
        } else if (update instanceof TLUpdateRestoreMessages) {
            TLUpdateRestoreMessages restoreMessages = (TLUpdateRestoreMessages) update;
            application.getEngine().onRestoredOnServer(restoreMessages.getMessages().toIntArray());
        } else if (update instanceof TLUpdateEncryption) {
            application.getEncryptionController().onUpdateEncryption(((TLUpdateEncryption) update).getChat());
        } else if (update instanceof TLUpdateEncryptedChatTyping) {
            application.getTypingStates().onEncryptedTyping(((TLUpdateEncryptedChatTyping) update).getChatId(), date);
        } else if (update instanceof TLUpdateNewEncryptedMessage) {
            application.getEncryptionController().onEncryptedMessage(((TLUpdateNewEncryptedMessage) update).getMessage());
            identity.qts = Math.max(identity.qts, ((TLUpdateNewEncryptedMessage) update).getQts());
        } else if (update instanceof TLUpdateEncryptedMessagesRead) {
            TLUpdateEncryptedMessagesRead read = (TLUpdateEncryptedMessagesRead) update;
            application.getEngine().onEncryptedReaded(read.getChatId(), read.getDate(), read.getMaxDate());
        } else if (update instanceof TLUpdateNewMessage) {
            onUpdateNewMessage((TLUpdateNewMessage) update);
        } else if (update instanceof TLUpdateNewAuthorization) {
            TLUpdateNewAuthorization authorization = (TLUpdateNewAuthorization) update;
            if (authorization.getLocation().length() > 0) {
                application.getNotifications().onAuthUnrecognized(authorization.getDevice(), authorization.getLocation());
            } else {
                application.getNotifications().onAuthUnrecognized(authorization.getDevice());
            }
        } else if (update instanceof TLUpdateActivation) {

        }
    }

    private void onUpdateNewMessage(TLUpdateNewMessage newMessage) {
        ArrayList<TLAbsMessage> messages = new ArrayList<TLAbsMessage>();
        messages.add(newMessage.getMessage());
        applyMessages(messages);
    }

    private void onUpdateShortChatMessage(TLUpdateShortChatMessage message) {
        if (application.getEngine().getDescriptionForPeer(PeerType.PEER_CHAT, message.getChatId()) == null) {
            throw new IllegalStateException();
        }

        boolean isAdded = application.getEngine().onNewMessage(PeerType.PEER_CHAT, message.getChatId(), message.getId(),
                message.getDate(), message.getFromId(), message.getMessage());
        if (message.getFromId() != application.getCurrentUid()) {
            if (isAdded) {
                onInMessageArrived(PeerType.PEER_CHAT, message.getChatId(), message.getId());
                DialogDescription description = application.getEngine().getDescriptionForPeer(PeerType.PEER_CHAT, message.getChatId());
                User sender = application.getEngine().getUser(message.getFromId());
                Group group = application.getEngine().getGroupsEngine().getGroup(message.getChatId());
                if (description != null && sender != null) {
                    application.getNotifications().onNewChatMessage(
                            sender.getDisplayName(),
                            sender.getUid(),
                            group.getTitle(), message.getMessage(),
                            message.getChatId(), message.getId(),
                            group.getAvatar());
                }
                application.getTypingStates().resetUserTyping(message.getFromId(), message.getChatId());
            }
        }
    }

    void onUpdateShortMessage(TLUpdateShortMessage message) {
        
        Log.e(TAG, "rpc <- update shortMsg "+message.getMessage());

        boolean isAdded = application.getEngine().onNewMessage(PeerType.PEER_USER, message.getFromId(), message.getId(),
                message.getDate(), message.getFromId(), message.getMessage());

        if (message.getFromId() != application.getCurrentUid()) {
            if (isAdded) {
                onInMessageArrived(PeerType.PEER_USER, message.getFromId(), message.getId());
                User sender = application.getEngine().getUser(message.getFromId());
                if (sender != null) {
                    application.getNotifications().onNewMessage(sender.getFirstName() + " " + sender.getLastName(),
                            message.getMessage(),
                            message.getFromId(), message.getId(),
                            sender.getPhoto());
                }
                application.getTypingStates().resetUserTyping(message.getFromId());
            }
        }
    }


    private void onCombined(TLUpdatesCombined combined, PackageIdentity identity) {
        ArrayList<TLAbsMessage> messages = new ArrayList<TLAbsMessage>();
        ArrayList<TLAbsUpdate> another = new ArrayList<TLAbsUpdate>();

        for (TLAbsUpdate update : combined.getUpdates()) {
            if (update instanceof TLUpdateNewMessage) {
                messages.add(((TLUpdateNewMessage) update).getMessage());
            } else {
                another.add(update);
            }
        }

        application.getEngine().onUsers(combined.getUsers());
        application.getEngine().getGroupsEngine().onGroupsUpdated(combined.getChats());
        applyMessages(messages);

        for (TLAbsUpdate update : another) {
            onUpdate(combined.getDate(), update, identity);
        }

        application.notifyUIUpdate();
    }

    private synchronized void onDifference(TLDifference difference) {

        for (TLAbsUpdate update : difference.getOtherUpdates()) {
            if (update instanceof TLUpdateMessageID) {
                application.getEngine().onUpdateMessageId(
                        ((TLUpdateMessageID) update).getRandomId(), ((TLUpdateMessageID) update).getId());
            }
        }


        application.getEngine().onUsers(difference.getUsers());
        application.getEngine().getGroupsEngine().onGroupsUpdated(difference.getChats());
        applyMessages(difference.getNewMessages());

        List<TLAbsEncryptedMessage> messages = difference.getNewEncryptedMessages();
        for (TLAbsEncryptedMessage encryptedMessage : messages) {
            application.getEncryptionController().onEncryptedMessage(encryptedMessage);
        }

        for (TLAbsUpdate update : difference.getOtherUpdates()) {
            if (update instanceof TLUpdateMessageID) {
                continue;
            }
            onUpdate(difference.getState().getDate(), update, null);
        }
        application.notifyUIUpdate();

        updateState.setFullState(
                difference.getState().getPts(),
                difference.getState().getSeq(),
                difference.getState().getDate(),
                difference.getState().getQts());

        dumpState();

        isInvalidated = false;
        onValidated();
    }

    private synchronized void onSliceDifference(TLDifferenceSlice slice) {
        for (TLAbsUpdate update : slice.getOtherUpdates()) {
            if (update instanceof TLUpdateMessageID) {
                application.getEngine().onUpdateMessageId(
                        ((TLUpdateMessageID) update).getRandomId(), ((TLUpdateMessageID) update).getId());
            }
        }

        application.getEngine().onUsers(slice.getUsers());
        application.getEngine().getGroupsEngine().onGroupsUpdated(slice.getChats());
        applyMessages(slice.getNewMessages());

        List<TLAbsEncryptedMessage> messages = slice.getNewEncryptedMessages();
        for (TLAbsEncryptedMessage encryptedMessage : messages) {
            application.getEncryptionController().onEncryptedMessage(encryptedMessage);
        }

        for (TLAbsUpdate update : slice.getOtherUpdates()) {
            if (update instanceof TLUpdateMessageID) {
                continue;
            }
            onUpdate(slice.getIntermediateState().getDate(), update, null);
        }
        application.notifyUIUpdate();

        updateState.setFullState(slice.getIntermediateState().getPts(),
                slice.getIntermediateState().getSeq(),
                slice.getIntermediateState().getDate(),
                slice.getIntermediateState().getQts());

        dumpState();
    }

    private void applyMessages(List<TLAbsMessage> messages) {
        ChatMessage[] resultMessages = application.getEngine().onUpdatedMessages(messages);

        for (int i = 0; i < resultMessages.length; i++) {
            if (!resultMessages[i].isOut() && resultMessages[i].getState() == MessageState.SENT) {
                onInMessageArrived(resultMessages[i].getPeerType(), resultMessages[i].getPeerId(), resultMessages[i].getMid());
            }
        }

        ChatMessage lastMessage = null;

        for (int i = 0; i < resultMessages.length; i++) {
            if (!resultMessages[i].isOut() && resultMessages[i].getState() == MessageState.SENT) {
                if (lastMessage == null) {
                    lastMessage = resultMessages[i];
                } else if (lastMessage.getDate() < resultMessages[i].getDate()) {
                    lastMessage = resultMessages[i];
                }
            }
        }

        if (lastMessage != null) {
            notifyAboutMessage(lastMessage);
        }
    }

    private void notifyAboutMessage(ChatMessage msg) {
        if (msg.getPeerType() != PeerType.PEER_USER && msg.getPeerType() != PeerType.PEER_CHAT) {
            return;
        }

        if (msg.getRawContentType() == ContentType.MESSAGE_TEXT) {
            User sender = application.getEngine().getUser(msg.getSenderId());
            if (msg.getPeerType() == PeerType.PEER_USER) {
                application.getNotifications().onNewMessage(
                        sender.getFirstName() + " " + sender.getLastName(),
                        msg.getMessage(),
                        msg.getSenderId(), msg.getMid(),
                        sender.getPhoto());
            } else if (msg.getPeerType() == PeerType.PEER_CHAT) {
                Group group = application.getEngine().getGroupsEngine().getGroup(msg.getPeerId());
                if (group != null) {
                    application.getNotifications().onNewChatMessage(
                            sender.getDisplayName(),
                            sender.getUid(),
                            group.getTitle(),
                            msg.getMessage(),
                            msg.getPeerId(), msg.getMid(),
                            sender.getPhoto());
                }
            }
        } else if (msg.getRawContentType() == ContentType.MESSAGE_CONTACT) {
            User sender = application.getEngine().getUser(msg.getSenderId());
            if (msg.getPeerType() == PeerType.PEER_USER) {
                application.getNotifications().onNewMessageContact(
                        sender.getFirstName() + " " + sender.getLastName(),
                        msg.getSenderId(), msg.getMid(),
                        sender.getPhoto());
            } else if (msg.getPeerId() == PeerType.PEER_CHAT) {
                Group group = application.getEngine().getGroupsEngine().getGroup(msg.getPeerId());
                if (group != null) {
                    application.getNotifications().onNewChatMessageContact(
                            sender.getDisplayName(),
                            sender.getUid(),
                            group.getTitle(),
                            msg.getPeerId(), msg.getMid(),
                            sender.getPhoto());
                }
            }
        } else if (msg.getRawContentType() == ContentType.MESSAGE_GEO) {
            User sender = application.getEngine().getUser(msg.getSenderId());
            if (msg.getPeerType() == PeerType.PEER_USER) {
                application.getNotifications().onNewMessageGeo(
                        sender.getDisplayName(),
                        msg.getSenderId(), msg.getMid(),
                        sender.getPhoto());
            } else if (msg.getPeerId() == PeerType.PEER_CHAT) {
                Group group = application.getEngine().getGroupsEngine().getGroup(msg.getPeerId());
                if (group != null) {
                    application.getNotifications().onNewChatMessageGeo(
                            sender.getDisplayName(),
                            sender.getUid(),
                            group.getTitle(),
                            msg.getPeerId(), msg.getMid(),
                            sender.getPhoto());
                }
            }
        } else if (msg.getRawContentType() == ContentType.MESSAGE_PHOTO) {
            User sender = application.getEngine().getUser(msg.getSenderId());
            if (msg.getPeerType() == PeerType.PEER_USER) {
                application.getNotifications().onNewMessagePhoto(
                        sender.getDisplayName(),
                        msg.getSenderId(), msg.getMid(),
                        sender.getPhoto());
            } else if (msg.getPeerId() == PeerType.PEER_CHAT) {
                Group group = application.getEngine().getGroupsEngine().getGroup(msg.getPeerId());
                if (group != null) {
                    application.getNotifications().onNewChatMessagePhoto(
                            sender.getDisplayName(),
                            sender.getUid(),
                            group.getTitle(),
                            msg.getPeerId(), msg.getMid(),
                            sender.getPhoto());
                }
            }
        } else if (msg.getRawContentType() == ContentType.MESSAGE_VIDEO) {
            User sender = application.getEngine().getUser(msg.getSenderId());
            if (msg.getPeerType() == PeerType.PEER_USER) {
                application.getNotifications().onNewMessageVideo(
                        sender.getDisplayName(),
                        msg.getSenderId(), msg.getMid(),
                        sender.getPhoto());
            } else if (msg.getPeerId() == PeerType.PEER_CHAT) {
                Group group = application.getEngine().getGroupsEngine().getGroup(msg.getPeerId());
                if (group != null) {
                    application.getNotifications().onNewChatMessageVideo(
                            sender.getDisplayName(),
                            sender.getUid(),
                            group.getTitle(),
                            msg.getPeerId(), msg.getMid(),
                            sender.getPhoto());
                }
            }
        } else if (msg.getRawContentType() == ContentType.MESSAGE_DOCUMENT) {
            User sender = application.getEngine().getUser(msg.getSenderId());
            if (msg.getPeerType() == PeerType.PEER_USER) {
                application.getNotifications().onNewMessageDoc(
                        sender.getDisplayName(),
                        msg.getSenderId(), msg.getMid(),
                        sender.getPhoto());
            } else if (msg.getPeerId() == PeerType.PEER_CHAT) {
                Group group = application.getEngine().getGroupsEngine().getGroup(msg.getPeerId());
                if (group != null) {
                    application.getNotifications().onNewChatMessageDoc(
                            sender.getDisplayName(),
                            sender.getUid(),
                            group.getTitle(),
                            msg.getPeerId(), msg.getMid(),
                            sender.getPhoto());
                }
            }
        } else if (msg.getRawContentType() == ContentType.MESSAGE_AUDIO) {
            User sender = application.getEngine().getUser(msg.getSenderId());
            if (msg.getPeerType() == PeerType.PEER_USER) {
                application.getNotifications().onNewMessageAudio(
                        sender.getDisplayName(),
                        msg.getSenderId(), msg.getMid(),
                        sender.getPhoto());
            } else if (msg.getPeerId() == PeerType.PEER_CHAT) {
                Group group = application.getEngine().getGroupsEngine().getGroup(msg.getPeerId());
                if (group != null) {
                    application.getNotifications().onNewChatMessageAudio(
                            sender.getDisplayName(),
                            sender.getUid(),
                            group.getTitle(),
                            msg.getPeerId(), msg.getMid(),
                            sender.getPhoto());
                }
            }
        }
    }

    private void onInMessageArrived(int peerType, int peerId, int mid) {
        if (application.getUiKernel().getOpenedChatPeerType() == peerType && application.getUiKernel().getOpenedChatPeerId() == peerId) {
            int maxMid = application.getEngine().getMessagesEngine().getMaxMidInDialog(peerType, peerId);
            application.getEngine().getDialogsEngine().onMaxLocalViewed(peerType, peerId, Math.max(maxMid, mid));
            application.getSyncKernel().getBackgroundSync().resetHistorySync();
        } else {
            application.getEngine().onNewUnreadMessageId(peerType, peerId, mid);
        }
    }

    public void dumpState() {
        Logger.d(TAG, "Current state: " + updateState.getPts() + ", " + updateState.getSeq() + ", " + updateState.getQts() + ", " + updateState.getDate());
    }

    public synchronized void destroy() {
        if (isDestroyed) {
            return;
        }
        isDestroyed = true;
        correctorHandler.removeMessages(0);
        correctorHandler.removeMessages(1);
        correctorLooper.quit();
        corrector.interrupt();
        further.clear();
    }

    public synchronized void clearData() {
        updateState.resetState();
    }
}
