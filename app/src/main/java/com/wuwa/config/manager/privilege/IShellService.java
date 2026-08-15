package com.wuwa.config.manager.privilege;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/** Minimal Binder contract used between the app and its Shizuku UserService. */
public interface IShellService extends IInterface {
    String DESCRIPTOR = "com.wuwa.config.manager.privilege.IShellService";

    String[] execute(String command, byte[] stdin, int timeoutSeconds) throws RemoteException;

    void destroy() throws RemoteException;

    abstract class Stub extends Binder implements IShellService {
        private static final int TRANSACTION_EXECUTE = IBinder.FIRST_CALL_TRANSACTION + 1;
        // Shizuku reserves this transaction for stopping a UserService process.
        private static final int TRANSACTION_DESTROY = IBinder.FIRST_CALL_TRANSACTION + 16777114;

        protected Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IShellService asInterface(IBinder binder) {
            if (binder == null) return null;
            IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            if (local instanceof IShellService) return (IShellService) local;
            return new Proxy(binder);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code >= IBinder.FIRST_CALL_TRANSACTION
                    && code <= IBinder.LAST_CALL_TRANSACTION) {
                data.enforceInterface(DESCRIPTOR);
            }
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            if (code == TRANSACTION_EXECUTE) {
                String command = data.readString();
                byte[] stdin = data.createByteArray();
                int timeoutSeconds = data.readInt();
                String[] result = execute(command, stdin, timeoutSeconds);
                reply.writeNoException();
                reply.writeStringArray(result);
                return true;
            }
            if (code == TRANSACTION_DESTROY) {
                destroy();
                reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static final class Proxy implements IShellService {
            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override
            public IBinder asBinder() {
                return remote;
            }

            @Override
            public String[] execute(String command, byte[] stdin, int timeoutSeconds)
                    throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(command);
                    data.writeByteArray(stdin);
                    data.writeInt(timeoutSeconds);
                    if (!remote.transact(TRANSACTION_EXECUTE, data, reply, 0)) {
                        throw new RemoteException("Shizuku Shell transaction failed");
                    }
                    reply.readException();
                    return reply.createStringArray();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void destroy() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    if (!remote.transact(TRANSACTION_DESTROY, data, reply, 0)) {
                        throw new RemoteException("Shizuku destroy transaction failed");
                    }
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
