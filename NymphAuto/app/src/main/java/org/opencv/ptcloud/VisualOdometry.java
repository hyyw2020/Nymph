//
// This file is auto-generated. Please don't modify it!
//
package org.opencv.ptcloud;

import org.opencv.core.Mat;
import org.opencv.features.DescriptorMatcher;
import org.opencv.features.Feature2D;
import org.opencv.ptcloud.OdometryParams;
import org.opencv.ptcloud.VisualOdometry;

// C++: class VisualOdometry
/**
 * Monocular visual odometry pipeline.
 *
 * State machine: NOT_INITIALIZED → INITIALIZING (H/F two-view bootstrap) → TRACKING
 * (per-frame PnP + local-map refinement). Tracking failure rewinds to INITIALIZING.
 *
 * Purely in-memory: feed images with REF: processFrame and read back the trajectory
 * and map with REF: getTrajectory / REF: getMap. See samples/slam for driving this
 * from a directory of images and writing the result to disk (e.g. COLMAP format).
 */
public class VisualOdometry {

    protected final long nativeObj;
    protected VisualOdometry(long addr) {
      nativeObj = addr;
      
    }

    public long getNativeObjAddr() { return nativeObj; }

    // internal usage only
    public static VisualOdometry __fromPtr__(long addr) { return new VisualOdometry(addr); }

    //
    // C++: static Ptr_VisualOdometry cv::slam::VisualOdometry::create(Ptr_Feature2D detector, Ptr_DescriptorMatcher matcher, Mat cameraMatrix, Mat distCoeffs = Mat(), OdometryParams params = OdometryParams())
    //

    public static VisualOdometry create(Feature2D detector, DescriptorMatcher matcher, Mat cameraMatrix, Mat distCoeffs, OdometryParams params) {
        return VisualOdometry.__fromPtr__(create_0(detector.getNativeObjAddr(), matcher.getNativeObjAddr(), cameraMatrix.nativeObj, distCoeffs.nativeObj, params.getNativeObjAddr()));
    }

    public static VisualOdometry create(Feature2D detector, DescriptorMatcher matcher, Mat cameraMatrix, Mat distCoeffs) {
        return VisualOdometry.__fromPtr__(create_1(detector.getNativeObjAddr(), matcher.getNativeObjAddr(), cameraMatrix.nativeObj, distCoeffs.nativeObj));
    }

    public static VisualOdometry create(Feature2D detector, DescriptorMatcher matcher, Mat cameraMatrix) {
        return VisualOdometry.__fromPtr__(create_2(detector.getNativeObjAddr(), matcher.getNativeObjAddr(), cameraMatrix.nativeObj));
    }


    //
    // C++:  bool cv::slam::VisualOdometry::processFrame(Mat image)
    //

    /**
     * Feed one image. Returns true if a pose was emitted.
     * @param image automatically generated
     * @return automatically generated
     */
    public boolean processFrame(Mat image) {
        return processFrame_0(nativeObj, image.nativeObj);
    }


    //
    // C++:  void cv::slam::VisualOdometry::reset()
    //

    /**
     * Reset to NOT_INITIALIZED, clearing map and trajectory.
     */
    public void reset() {
        reset_0(nativeObj);
    }


    //
    // C++:  OdometryState cv::slam::VisualOdometry::getState()
    //

    // Return type 'OdometryState' is not supported, skipping the function


    //
    // C++:  Matx44d cv::slam::VisualOdometry::getLastPose()
    //

    // Return type 'Matx44d' is not supported, skipping the function


    //
    // C++:  int cv::slam::VisualOdometry::getNumKeyframes()
    //

    public int getNumKeyframes() {
        return getNumKeyframes_0(nativeObj);
    }


    //
    // C++:  int cv::slam::VisualOdometry::getNumMapPoints()
    //

    public int getNumMapPoints() {
        return getNumMapPoints_0(nativeObj);
    }


    //
    // C++:  vector_Matx44d cv::slam::VisualOdometry::getTrajectory()
    //

    // Return type 'vector_Matx44d' is not supported, skipping the function


    //
    // C++:  OdometryParams cv::slam::VisualOdometry::getParams()
    //

    public OdometryParams getParams() {
        return new OdometryParams(getParams_0(nativeObj));
    }


    //
    // C++:  void cv::slam::VisualOdometry::setParams(OdometryParams params)
    //

    public void setParams(OdometryParams params) {
        setParams_0(nativeObj, params.getNativeObjAddr());
    }


    @Override
    protected void finalize() throws Throwable {
        delete(nativeObj);
    }



    // C++: static Ptr_VisualOdometry cv::slam::VisualOdometry::create(Ptr_Feature2D detector, Ptr_DescriptorMatcher matcher, Mat cameraMatrix, Mat distCoeffs = Mat(), OdometryParams params = OdometryParams())
    private static native long create_0(long detector_nativeObj, long matcher_nativeObj, long cameraMatrix_nativeObj, long distCoeffs_nativeObj, long params_nativeObj);
    private static native long create_1(long detector_nativeObj, long matcher_nativeObj, long cameraMatrix_nativeObj, long distCoeffs_nativeObj);
    private static native long create_2(long detector_nativeObj, long matcher_nativeObj, long cameraMatrix_nativeObj);

    // C++:  bool cv::slam::VisualOdometry::processFrame(Mat image)
    private static native boolean processFrame_0(long nativeObj, long image_nativeObj);

    // C++:  void cv::slam::VisualOdometry::reset()
    private static native void reset_0(long nativeObj);

    // C++:  int cv::slam::VisualOdometry::getNumKeyframes()
    private static native int getNumKeyframes_0(long nativeObj);

    // C++:  int cv::slam::VisualOdometry::getNumMapPoints()
    private static native int getNumMapPoints_0(long nativeObj);

    // C++:  OdometryParams cv::slam::VisualOdometry::getParams()
    private static native long getParams_0(long nativeObj);

    // C++:  void cv::slam::VisualOdometry::setParams(OdometryParams params)
    private static native void setParams_0(long nativeObj, long params_nativeObj);

    // native support for java finalize() or cleaner
    private static native void delete(long nativeObj);

}
