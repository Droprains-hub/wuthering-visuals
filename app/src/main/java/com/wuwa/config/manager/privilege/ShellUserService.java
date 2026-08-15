package com.wuwa.config.manager.privilege;

import android.os.RemoteException;

/** Runs inside the Shizuku user-service process with root or adb-shell identity. */
public final class ShellUserService extends IShellService.Stub {
    public ShellUserService() {}

    @Override
    public String[] execute(String command, byte[] stdin, int timeoutSeconds) {
        if (command == null || command.trim().isEmpty()) {
            return new String[]{"64", "", "Shell 命令为空"};
        }
        try {
            Process process = new ProcessBuilder("/system/bin/sh", "-c", command).start();
            ShellResult result = ShellProcessRunner.run(process, stdin, timeoutSeconds);
            return new String[]{
                    Integer.toString(result.getExitCode()),
                    result.getStdout(),
                    result.getStderr()
            };
        } catch (Throwable error) {
            String detail = error.getMessage();
            if (detail == null || detail.trim().isEmpty()) {
                detail = error.getClass().getSimpleName();
            }
            return new String[]{"70", "", detail};
        }
    }

    @Override
    public void destroy() throws RemoteException {
        System.exit(0);
    }
}

