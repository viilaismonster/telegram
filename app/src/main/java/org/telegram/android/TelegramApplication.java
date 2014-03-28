package org.telegram.android;

import android.app.Application;
import android.os.Environment;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;
import org.telegram.android.config.NotificationSettings;
import org.telegram.android.config.UserSettings;
import org.telegram.android.config.WallpaperHolder;
import org.telegram.android.core.*;
import org.telegram.android.core.background.MediaSender;
import org.telegram.android.core.background.MessageSender;
import org.telegram.android.core.background.SelfDestructProcessor;
import org.telegram.android.core.background.UpdateProcessor;
import org.telegram.android.core.engines.ModelEngine;
import org.telegram.android.core.files.UploadController;
import org.telegram.android.critical.ApiStorage;
import org.telegram.android.kernel.*;
import org.telegram.android.media.DownloadManager;
import org.telegram.android.reflection.CrashHandler;
import org.telegram.android.ui.EmojiProcessor;
import org.telegram.android.ui.UiResponsibility;
import org.telegram.android.util.NativeLibLoader;
import org.telegram.api.engine.TelegramApi;
import org.telegram.bootstrap.DcInitialConfig;
import org.telegram.mtproto.MTProto;
import org.telegram.mtproto.schedule.Scheduller;
import org.telegram.mtproto.transport.TcpContextCallback;
import org.telegram.tl.TLObject;

import java.io.*;

/**
 * Author: Korshakov Stepan
 * Created: 22.07.13 0:55
 */
public class TelegramApplication extends Application {

    private ApplicationKernel kernel;
    private KernelsLoader kernelsLoader;

    {
        Scheduller.injector = new Scheduller.Injector() {
            @Override
            public int postMessageDelayed(TLObject object, boolean isRpc, long timeout, int delay, int contextId, boolean highPrioroty) {
                Log.e("Telegram RPC", "mt -> " + object.getClass().getSimpleName() + " " + System.currentTimeMillis() / 1000);
                return 0;
            }
        };

        MTProto.injector = new MTProto.Injector() {
            @Override
            protected void onTcpFixedThreadReconnect(MTProto mtProto, String host, int port, boolean useChecksum, TcpContextCallback tcpListener) {
                Log.e("Telegram TCP","#### tcp connect "+host+":"+port+" ####");
            }

            @Override
            public void onReceiveMTMessage(TLObject object) {
                Log.e("Telegram RPC", "mt <- " + object.getClass().getSimpleName() + " " + System.currentTimeMillis() / 1000);
            }

            @Override
            public void onTcpFixedThreadFailed(MTProto mtProto, String host, int port, boolean useChecksum, TcpContextCallback tcpListener,Exception e) {
                Log.e("Telegram TCP","#### tcp connect failed "+host+":"+port+" ####",e);
            }
        };

        TelegramApi.injector = new TelegramApi.Injector() {

            @Override
            public void e(String tag, String message) {
                Log.e(tag, message);
            }
        };
    }

    @Override
    public void onCreate() {
        if (kernel != null) {
            super.onCreate();
            return;
        }

        // customized configure
        try {
            final String configPath = Environment.getExternalStorageDirectory().getPath()+"/";
            FileInputStream fi = new FileInputStream(configPath + "teleopen.conf");
            BufferedReader reader = new BufferedReader(new InputStreamReader(fi));
            StringBuilder log = new StringBuilder();
            String line = null;
            while((line = reader.readLine()) != null) {
                String key = line.indexOf("=")>0?
                          line.substring(0, line.indexOf("=")).trim().toLowerCase()
                        : line.trim();
                String val = line.substring(line.indexOf("=")+1).trim();
                if(key.equals("dc")) {
                    if(val.indexOf(":")>0) {
                        DcInitialConfig.ADDRESS = val.substring(0, val.indexOf(":")).trim();
                        DcInitialConfig.PORT = Integer.parseInt(val.substring(val.indexOf(":")+1).trim());
                    } else {
                        DcInitialConfig.ADDRESS = val.trim();
                    }
                    log.append("dc->"+DcInitialConfig.ADDRESS+":"+DcInitialConfig.PORT+"\n");
                } else if(key.equals("clean")
                        || (key.equals("cleanonce") && !new File(configPath+"teleopen_clean_lock").exists())) {
                    deleteDir(getApplicationContext().getFilesDir());
                    deleteDir(getApplicationContext().getCacheDir());
                    PreferenceManager.getDefaultSharedPreferences(this).edit().clear().commit();
                    log.append("do cleanup\n");
                    FileOutputStream lock = new FileOutputStream(new File(configPath+"teleopen_clean_lock"));
                    lock.close();
                }
            }
            reader.close();
            fi.close();
            Toast.makeText(this.getApplicationContext(), "conf\n"+log.toString().trim(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this.getApplicationContext(),
                    "read sdcard://teleopen.conf failed: "+e.getMessage(), Toast.LENGTH_SHORT).show();
        }

        NativeLibLoader.initNativeLibs(this);

        CrashHandler.init(this);
        kernel = new ApplicationKernel(this);
        super.onCreate();

        kernelsLoader = new KernelsLoader();
        kernelsLoader.stagedLoad(kernel);
    }

    public static void deleteDir (File dir)
    {
        if (dir.isDirectory())
        {
            File[] files = dir.listFiles();
            for (File f:files)
            {
                deleteDir(f);
            }
            dir.delete();
        }
        else
            dir.delete();
    }

    public KernelsLoader getKernelsLoader() {
        return kernelsLoader;
    }

    public boolean isRTL() {
        return kernel.getTechKernel().getTechReflection().isRtl();
    }

    public boolean isSlow() {
        return kernel.getTechKernel().getTechReflection().isSlow();
    }

    public boolean isLoggedIn() {
        return kernel.getAuthKernel().isLoggedIn();
    }

    public int getCurrentUid() {
        return kernel.getAuthKernel().getApiStorage().getObj().getUid();
    }

    public UploadController getUploadController() {
        return kernel.getFileKernel().getUploadController();
    }

    public UserSource getUserSource() {
        return kernel.getDataSourceKernel().getUserSource();
    }

    public TypingStates getTypingStates() {
        return kernel.getSyncKernel().getTypingStates();
    }

    public MessageSender getMessageSender() {
        return kernel.getSyncKernel().getMessageSender();
    }

    public MediaSender getMediaSender() {
        return kernel.getSyncKernel().getMediaSender();
    }

    public DialogSource getDialogSource() {
        return kernel.getDataSourceKernel().getDialogSource();
    }

    public ContactsSource getContactsSource() {
        return kernel.getDataSourceKernel().getContactsSource();
    }

    public EmojiProcessor getEmojiProcessor() {
        return getUiKernel().getEmojiProcessor();
    }

    public Notifications getNotifications() {
        return getUiKernel().getNotifications();
    }

    public NotificationSettings getNotificationSettings() {
        return kernel.getSettingsKernel().getNotificationSettings();
    }

    public void notifyUIUpdate() {
        getDataSourceKernel().notifyUIUpdate();
    }

    public UpdateProcessor getUpdateProcessor() {
        return kernel.getSyncKernel().getUpdateProcessor();
    }


    public ModelEngine getEngine() {
        return kernel.getStorageKernel().getModel();
    }

    public ApiStorage getApiStorage() {
        return kernel.getAuthKernel().getApiStorage();
    }

    public TelegramApi getApi() {
        return kernel.getApiKernel().getApi();
    }

    public ChatSource getChatSource() {
        return kernel.getDataSourceKernel().getChatSource();
    }

    public DownloadManager getDownloadManager() {
        return kernel.getFileKernel().getDownloadManager();
    }

    public UiResponsibility getResponsibility() {
        return kernel.getUiKernel().getResponsibility();
    }

    public UserSettings getUserSettings() {
        return kernel.getSettingsKernel().getUserSettings();
    }

    public WallpaperHolder getWallpaperHolder() {
        return kernel.getUiKernel().getWallpaperHolder();
    }

    public ConnectionMonitor getMonitor() {
        return kernel.getTechKernel().getMonitor();
    }

    public EncryptionController getEncryptionController() {
        return kernel.getEncryptedKernel().getEncryptionController();
    }

    public EncryptedChatSource getEncryptedChatSource() {
        return kernel.getDataSourceKernel().getEncryptedChatSource();
    }

    public TextSaver getTextSaver() {
        return getUiKernel().getTextSaver();
    }

    public SelfDestructProcessor getSelfDestructProcessor() {
        return kernel.getEncryptedKernel().getSelfDestructProcessor();
    }

    public VersionHolder getVersionHolder() {
        return kernel.getTechKernel().getVersionHolder();
    }

    public ApplicationKernel getKernel() {
        return kernel;
    }

    public UiKernel getUiKernel() {
        return kernel.getUiKernel();
    }

    public TechKernel getTechKernel() {
        return kernel.getTechKernel();
    }

    public SearchKernel getSearchKernel() {
        return kernel.getSearchKernel();
    }

    public DataSourceKernel getDataSourceKernel() {
        return kernel.getDataSourceKernel();
    }

    public SettingsKernel getSettingsKernel() {
        return kernel.getSettingsKernel();
    }

    public SyncKernel getSyncKernel() {
        return kernel.getSyncKernel();
    }
}