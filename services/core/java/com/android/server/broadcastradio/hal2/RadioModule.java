/**
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.broadcastradio.hal2;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.broadcastradio.V2_0.AmFmRegionConfig;
import android.hardware.broadcastradio.V2_0.Announcement;
import android.hardware.broadcastradio.V2_0.DabTableEntry;
import android.hardware.broadcastradio.V2_0.IAnnouncementListener;
import android.hardware.broadcastradio.V2_0.IBroadcastRadio;
import android.hardware.broadcastradio.V2_0.ICloseHandle;
import android.hardware.broadcastradio.V2_0.ITunerCallback;
import android.hardware.broadcastradio.V2_0.ITunerSession;
import android.hardware.broadcastradio.V2_0.ProgramInfo;
import android.hardware.broadcastradio.V2_0.ProgramListChunk;
import android.hardware.broadcastradio.V2_0.ProgramSelector;
import android.hardware.broadcastradio.V2_0.Result;
import android.hardware.broadcastradio.V2_0.VendorKeyValue;
import android.hardware.radio.RadioManager;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.MutableInt;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

class RadioModule {
    private static final String TAG = "BcRadio2Srv.module";

    @NonNull private final IBroadcastRadio mService;
    @NonNull public final RadioManager.ModuleProperties mProperties;

    private final Object mLock = new Object();
    @NonNull private final Handler mHandler;

    @GuardedBy("mLock")
    private ITunerSession mHalTunerSession;

    // Tracks antenna state reported by HAL (if any).
    @GuardedBy("mLock")
    private Boolean mAntennaConnected = null;

    @GuardedBy("mLock")
    private RadioManager.ProgramInfo mProgramInfo = null;

    // Callback registered with the HAL to relay callbacks to AIDL clients.
    private final ITunerCallback mHalTunerCallback = new ITunerCallback.Stub() {
        @Override
        public void onTuneFailed(int result, ProgramSelector programSelector) {
            lockAndFireLater(() -> {
                android.hardware.radio.ProgramSelector csel =
                        Convert.programSelectorFromHal(programSelector);
                fanoutAidlCallbackLocked(cb -> cb.onTuneFailed(result, csel));
            });
        }

        @Override
        public void onCurrentProgramInfoChanged(ProgramInfo halProgramInfo) {
            lockAndFireLater(() -> {
                mProgramInfo = Convert.programInfoFromHal(halProgramInfo);
                fanoutAidlCallbackLocked(cb -> cb.onCurrentProgramInfoChanged(mProgramInfo));
            });
        }

        @Override
        public void onProgramListUpdated(ProgramListChunk programListChunk) {
            // TODO: Cache per-AIDL client filters, send union of filters to HAL, use filters to fan
            // back out to clients.
            lockAndFireLater(() -> {
                android.hardware.radio.ProgramList.Chunk chunk =
                        Convert.programListChunkFromHal(programListChunk);
                fanoutAidlCallbackLocked(cb -> cb.onProgramListUpdated(chunk));
            });
        }

        @Override
        public void onAntennaStateChange(boolean connected) {
            lockAndFireLater(() -> {
                mAntennaConnected = connected;
                fanoutAidlCallbackLocked(cb -> cb.onAntennaState(connected));
            });
        }

        @Override
        public void onParametersUpdated(ArrayList<VendorKeyValue> parameters) {
            lockAndFireLater(() -> {
                Map<String, String> cparam = Convert.vendorInfoFromHal(parameters);
                fanoutAidlCallbackLocked(cb -> cb.onParametersUpdated(cparam));
            });
        }
    };

    // Collection of active AIDL tuner sessions created through openSession().
    @GuardedBy("mLock")
    private final Set<TunerSession> mAidlTunerSessions = new HashSet<>();

    private RadioModule(@NonNull IBroadcastRadio service,
            @NonNull RadioManager.ModuleProperties properties) throws RemoteException {
        mProperties = Objects.requireNonNull(properties);
        mService = Objects.requireNonNull(service);
        mHandler = new Handler(Looper.getMainLooper());
    }

    public static @Nullable RadioModule tryLoadingModule(int idx, @NonNull String fqName) {
        try {
            IBroadcastRadio service = IBroadcastRadio.getService(fqName);
            if (service == null) return null;

            Mutable<AmFmRegionConfig> amfmConfig = new Mutable<>();
            service.getAmFmRegionConfig(false, (result, config) -> {
                if (result == Result.OK) amfmConfig.value = config;
            });

            Mutable<List<DabTableEntry>> dabConfig = new Mutable<>();
            service.getDabRegionConfig((result, config) -> {
                if (result == Result.OK) dabConfig.value = config;
            });

            RadioManager.ModuleProperties prop = Convert.propertiesFromHal(idx, fqName,
                    service.getProperties(), amfmConfig.value, dabConfig.value);

            return new RadioModule(service, prop);
        } catch (RemoteException ex) {
            Slog.e(TAG, "failed to load module " + fqName, ex);
            return null;
        }
    }

    public @NonNull IBroadcastRadio getService() {
        return mService;
    }

    public @NonNull TunerSession openSession(@NonNull android.hardware.radio.ITunerCallback userCb)
            throws RemoteException {
        synchronized (mLock) {
            if (mHalTunerSession == null) {
                Mutable<ITunerSession> hwSession = new Mutable<>();
                mService.openSession(mHalTunerCallback, (result, session) -> {
                    Convert.throwOnError("openSession", result);
                    hwSession.value = session;
                });
                mHalTunerSession = Objects.requireNonNull(hwSession.value);
            }
            TunerSession tunerSession = new TunerSession(this, mHalTunerSession, userCb);
            mAidlTunerSessions.add(tunerSession);

            // Propagate state to new client. Note: These callbacks are invoked while holding mLock
            // to prevent race conditions with new callbacks from the HAL.
            if (mAntennaConnected != null) {
                userCb.onAntennaState(mAntennaConnected);
            }
            if (mProgramInfo != null) {
                userCb.onCurrentProgramInfoChanged(mProgramInfo);
            }

            return tunerSession;
        }
    }

    public void closeSessions(Integer error) {
        // Copy the contents of mAidlTunerSessions into a local array because TunerSession.close()
        // must be called without mAidlTunerSessions locked because it can call
        // onTunerSessionClosed().
        TunerSession[] tunerSessions;
        synchronized (mLock) {
            tunerSessions = new TunerSession[mAidlTunerSessions.size()];
            mAidlTunerSessions.toArray(tunerSessions);
            mAidlTunerSessions.clear();
        }
        for (TunerSession tunerSession : tunerSessions) {
            tunerSession.close(error);
        }
    }

    void onTunerSessionClosed(TunerSession tunerSession) {
        synchronized (mLock) {
            mAidlTunerSessions.remove(tunerSession);
            if (mAidlTunerSessions.isEmpty() && mHalTunerSession != null) {
                Slog.v(TAG, "closing HAL tuner session");
                try {
                    mHalTunerSession.close();
                } catch (RemoteException ex) {
                    Slog.e(TAG, "mHalTunerSession.close() failed: ", ex);
                }
                mHalTunerSession = null;
            }
        }
    }

    // add to mHandler queue, but ensure the runnable holds mLock when it gets executed
    private void lockAndFireLater(Runnable r) {
        mHandler.post(() -> {
            synchronized (mLock) {
                r.run();
            }
        });
    }

    interface AidlCallbackRunnable {
        void run(android.hardware.radio.ITunerCallback callback) throws RemoteException;
    }

    // Invokes runnable with each TunerSession currently open.
    void fanoutAidlCallback(AidlCallbackRunnable runnable) {
        lockAndFireLater(() -> fanoutAidlCallbackLocked(runnable));
    }

    private void fanoutAidlCallbackLocked(AidlCallbackRunnable runnable) {
        for (TunerSession tunerSession : mAidlTunerSessions) {
            try {
                runnable.run(tunerSession.mCallback);
            } catch (DeadObjectException ex) {
                // The other side died without calling close(), so just purge it from our
                // records.
                Slog.e(TAG, "Removing dead TunerSession");
                mAidlTunerSessions.remove(tunerSession);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to invoke ITunerCallback: ", ex);
            }
        }
    }

    public android.hardware.radio.ICloseHandle addAnnouncementListener(@NonNull int[] enabledTypes,
            @NonNull android.hardware.radio.IAnnouncementListener listener) throws RemoteException {
        ArrayList<Byte> enabledList = new ArrayList<>();
        for (int type : enabledTypes) {
            enabledList.add((byte)type);
        }

        MutableInt halResult = new MutableInt(Result.UNKNOWN_ERROR);
        Mutable<ICloseHandle> hwCloseHandle = new Mutable<>();
        IAnnouncementListener hwListener = new IAnnouncementListener.Stub() {
            public void onListUpdated(ArrayList<Announcement> hwAnnouncements)
                    throws RemoteException {
                listener.onListUpdated(hwAnnouncements.stream().
                    map(a -> Convert.announcementFromHal(a)).collect(Collectors.toList()));
            }
        };

        synchronized (mService) {
            mService.registerAnnouncementListener(enabledList, hwListener, (result, closeHnd) -> {
                halResult.value = result;
                hwCloseHandle.value = closeHnd;
            });
        }
        Convert.throwOnError("addAnnouncementListener", halResult.value);

        return new android.hardware.radio.ICloseHandle.Stub() {
            public void close() {
                try {
                    hwCloseHandle.value.close();
                } catch (RemoteException ex) {
                    Slog.e(TAG, "Failed closing announcement listener", ex);
                }
            }
        };
    }

    Bitmap getImage(int id) {
        if (id == 0) throw new IllegalArgumentException("Image ID is missing");

        byte[] rawImage;
        synchronized (mService) {
            List<Byte> rawList = Utils.maybeRethrow(() -> mService.getImage(id));
            rawImage = new byte[rawList.size()];
            for (int i = 0; i < rawList.size(); i++) {
                rawImage[i] = rawList.get(i);
            }
        }

        if (rawImage == null || rawImage.length == 0) return null;

        return BitmapFactory.decodeByteArray(rawImage, 0, rawImage.length);
    }
}
