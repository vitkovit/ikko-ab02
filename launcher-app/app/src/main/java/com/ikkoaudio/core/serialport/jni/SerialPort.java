package com.ikkoaudio.core.serialport.jni;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;

/**
 * Thin JNI wrapper over the stock IKKO {@code libserial_port.so}.
 *
 * The native library binds its methods by the mangled name
 * {@code Java_com_ikkoaudio_core_serialport_jni_SerialPort_*}, so this class MUST
 * keep this exact package + name for the reused .so to link. The constructor mirrors
 * the original (device file + baud); the stock {@code su chmod} fallback is dropped
 * because /dev/ttyS1 is already mode 0666 on this device (and SELinux is permissive).
 */
public final class SerialPort {

    private FileDescriptor mFd;

    public SerialPort(File device, int baud) throws IOException {
        System.loadLibrary("serial_port");
        FileDescriptor fd = open(device.getAbsolutePath(), baud);
        this.mFd = fd;
        if (fd == null) throw new IOException("serial open failed: " + device);
        initPipe();
        setSerialFd(fd);
    }

    private native void initPipe();
    private native FileDescriptor open(String path, int baud);
    private native void setSerialFd(FileDescriptor fd);
    public native void close();
    public native void destroyPipe();
    public native int readOnce(byte[] buf);
    public native void sendStopSignal();
    public native void writeBytes(byte[] data);
}
