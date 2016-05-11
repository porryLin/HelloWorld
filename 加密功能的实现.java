加密功能的实现：

1. packages/apps/Settings/src/com/android/CrptKeeperConfirm.java
    点击“加密平板”按钮后，输入PIN码，再次点击“加密平板”按钮，触发监听器mFinalClickListener，调用内部类Blank，最后转到MountService服务
     mountService.encryptStorage(args.getString("password"));

2. framework/base/services/java/com/android/server/MountService.java

    encryptStorage()方法的实现如下：   
    public int encryptStorage(String password) {
        if (TextUtils.isEmpty(password)) {           /* 判断加密密码是否为空，如果为空则抛出异常 */
            throw new IllegalArgumentException("password cannot be empty");
        }
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
            "no permission to access the crypt keeper");     /* 判断是否拥有CRYPT_KEEPER的权限，如果没有则抛出异常 */

        waitForReady();                  /* 等待回调方法onDaemonConnected()执行完成 */
        if (DEBUG_EVENTS) {
            Slog.i(TAG, "encrypting storage...");
        }

        try {
               /*
                * 给vold发送命令 "cryptfs enablecrypto inplace "
                */
            mConnector.execute("cryptfs", "enablecrypto", "inplace", password);
        } catch (NativeDaemonConnectorException e) {
            // Encryption failed
            return e.getCode();
        }

        return 0;
    }

    private void waitForReady() {
        waitForLatch(mConnectedSignal);
    }

    private void waitForLatch(CountDownLatch latch) {
        for (;;) {
            try {
                    // 阻塞当前的线程等待latch计时器清零，该计时器在回调方法onDaemonConnected()中通过mConnectedSignal.countDown()清零
                if (latch.await(5000, TimeUnit.MILLISECONDS)) {
                    return;
                } else {
                    Slog.w(TAG, "Thread " + Thread.currentThread().getName()
                            + " still waiting for MountService ready...");
                }
            } catch (InterruptedException e) {
                Slog.w(TAG, "Interrupt while waiting for MountService to be ready.");
            }
        }
    }


    /**
     * Callback from NativeDaemonConnector
      * 回调方法
     */
    public void onDaemonConnected() {
        /*
         * Since we'll be calling back into the NativeDaemonConnector,
         * we need to do our work in a new thread.
         */
        new Thread("MountService#onDaemonConnected") {
            @Override
            public void run() {
                /**
                 * Determine media state and UMS detection status
                 */
                try {
                    final String[] vols = NativeDaemonEvent.filterMessageList(
                            mConnector.executeForList("volume", "list"),
                            VoldResponseCode.VolumeListResult);
                    for (String volstr : vols) {
                        String[] tok = volstr.split(" ");
                        // FMT: <label> <mountpoint> <state>
                        String path = tok[1];
                        String state = Environment.MEDIA_REMOVED;

                        final StorageVolume volume;
                        synchronized (mVolumesLock) {
                            volume = mVolumesByPath.get(path);
                        }

                        int st = Integer.parseInt(tok[2]);
                        if (st == VolumeState.NoMedia) {
                            state = Environment.MEDIA_REMOVED;
                        } else if (st == VolumeState.Idle) {
                            state = Environment.MEDIA_UNMOUNTED;
                        } else if (st == VolumeState.Mounted) {
                            state = Environment.MEDIA_MOUNTED;
                            Slog.i(TAG, "Media already mounted on daemon connection");
                        } else if (st == VolumeState.Shared) {
                            state = Environment.MEDIA_SHARED;
                            Slog.i(TAG, "Media shared on daemon connection");
                        } else {
                            throw new Exception(String.format("Unexpected state %d", st));
                        }

                        if (state != null) {
                            if (DEBUG_EVENTS) Slog.i(TAG, "Updating valid state " + state);
                            updatePublicVolumeState(volume, state);
                        }
                    }
                } catch (Exception e) {
                    Slog.e(TAG, "Error processing initial volume state", e);
                    final StorageVolume primary = getPrimaryPhysicalVolume();
                    if (primary != null) {
                        updatePublicVolumeState(primary, Environment.MEDIA_REMOVED);
                    }
                }

                /*
                 * Now that we've done our initialization, release
                 * the hounds!
                 */
                mConnectedSignal.countDown();

                // Let package manager load internal ASECs.
                mPms.scanAvailableAsecs();

                // Notify people waiting for ASECs to be scanned that it's done.
                mAsecsScanned.countDown();
            }
        }.start();
    }

3. framework/base/services/java/com/android/server/NativeDaemonConnector.java

    在MountService.java的构造函数中，该类已生成实例mConnector，并调用：
    public void Mountservice(Context context) {
          ...
        mConnector = new NativeDaemonConnector(this, "vold", MAX_CONTAINERS * 2, VOLD_TAG, 25);

        Thread thread = new Thread(mConnector, VOLD_TAG);
        thread.start();
          ...
     }
    
    在run()中调用listenToSocket()，在 listenToSocket()中通过 mCallbacks.onDaemonConnected()
    回调MountService.java的onDaemonConnected()方法。



4. android/system/vold/cryptfs.c

   int cryptfs_enable(char *howarg, char *passwd) {

   static int cryptfs_enable_inplace(char *crypto_blkdev, char *real_blkdev, off64_t size,
                                  off64_t *size_already_done, off64_t tot_size) {

   


