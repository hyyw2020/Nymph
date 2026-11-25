//
// This file is auto-generated. Please don't modify it!
//
package org.opencv.videoio;



// C++: class VideoDeviceInfo
/**
 * Camera device reported by a videoio backend
 */
public class VideoDeviceInfo {

    protected final long nativeObj;
    protected VideoDeviceInfo(long addr) {
      nativeObj = addr;
      
    }

    public long getNativeObjAddr() { return nativeObj; }

    // internal usage only
    public static VideoDeviceInfo __fromPtr__(long addr) { return new VideoDeviceInfo(addr); }

    //
    // C++:   cv::VideoDeviceInfo::VideoDeviceInfo()
    //

    public VideoDeviceInfo() {
        nativeObj = VideoDeviceInfo_0();
    }


    //
    // C++: int VideoDeviceInfo::cam_idx
    //

    public int get_cam_idx() {
        return get_cam_idx_0(nativeObj);
    }


    //
    // C++: void VideoDeviceInfo::cam_idx
    //

    public void set_cam_idx(int cam_idx) {
        set_cam_idx_0(nativeObj, cam_idx);
    }


    //
    // C++: string VideoDeviceInfo::cam_name
    //

    public String get_cam_name() {
        return get_cam_name_0(nativeObj);
    }


    //
    // C++: void VideoDeviceInfo::cam_name
    //

    public void set_cam_name(String cam_name) {
        set_cam_name_0(nativeObj, cam_name);
    }


    //
    // C++: VideoCaptureAPIs VideoDeviceInfo::backend
    //

    public int get_backend() {
        return get_backend_0(nativeObj);
    }


    //
    // C++: void VideoDeviceInfo::backend
    //

    public void set_backend(int backend) {
        set_backend_0(nativeObj, backend);
    }


    @Override
    protected void finalize() throws Throwable {
        delete(nativeObj);
    }



    // C++:   cv::VideoDeviceInfo::VideoDeviceInfo()
    private static native long VideoDeviceInfo_0();

    // C++: int VideoDeviceInfo::cam_idx
    private static native int get_cam_idx_0(long nativeObj);

    // C++: void VideoDeviceInfo::cam_idx
    private static native void set_cam_idx_0(long nativeObj, int cam_idx);

    // C++: string VideoDeviceInfo::cam_name
    private static native String get_cam_name_0(long nativeObj);

    // C++: void VideoDeviceInfo::cam_name
    private static native void set_cam_name_0(long nativeObj, String cam_name);

    // C++: VideoCaptureAPIs VideoDeviceInfo::backend
    private static native int get_backend_0(long nativeObj);

    // C++: void VideoDeviceInfo::backend
    private static native void set_backend_0(long nativeObj, int backend);

    // native support for java finalize() or cleaner
    private static native void delete(long nativeObj);

}
