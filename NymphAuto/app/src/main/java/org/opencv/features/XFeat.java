//
// This file is auto-generated. Please don't modify it!
//
package org.opencv.features;

import java.util.ArrayList;
import java.util.List;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Size;
import org.opencv.features.Feature2D;
import org.opencv.features.XFeat;
import org.opencv.utils.Converters;

// C++: class XFeat
/**
 * XFeat feature detector and descriptor, based on a DNN model.
 *
 * XFeat is a compact learned local-feature extractor. This class wraps an ONNX export through
 * cv::dnn::Net and exposes score-map detections with 64-D float descriptors under the standard
 * cv::Feature2D interface.
 *
 * The class assumes the ONNX model has a single grayscale input tensor N×1×H×W in [0, 1] and
 * returns descriptor and score maps. The descriptor map must have 64 channels, and the keypoint
 * logit map must have either 64 channels (no extra class) or 65 channels, where the last channel
 * is treated as a dustbin/background class used only in softmax normalization. Images are resized
 * with preserved aspect ratio and padded to the configured network input size.
 */
public class XFeat extends Feature2D {

    protected XFeat(long addr) { super(addr); }

    // internal usage only
    public static XFeat __fromPtr__(long addr) { return new XFeat(addr); }

    //
    // C++: static Ptr_XFeat cv::XFeat::create(String modelPath, int maxKeypoints = -1, float scoreThreshold = 0.5f, Size inputSize = Size(640, 640), int backendId = 0, int targetId = 0)
    //

    /**
     * Creates an XFeat detector.
     *     @param modelPath Path to the XFeat ONNX model.
     *     @param maxKeypoints Maximum number of keypoints to return per image. The strongest
     *                         responses are kept; -1 keeps all detections.
     *     @param scoreThreshold Discard keypoints with network score not greater than this value.
     *     @param inputSize Input size fed to the network, default Size(640, 640).
     *     @param backendId DNN backend identifier (see cv::dnn::Backend); 0 = DNN_BACKEND_DEFAULT.
     *     @param targetId  DNN target identifier (see cv::dnn::Target);  0 = DNN_TARGET_CPU.
     * @return automatically generated
     */
    public static XFeat create(String modelPath, int maxKeypoints, float scoreThreshold, Size inputSize, int backendId, int targetId) {
        return XFeat.__fromPtr__(create_0(modelPath, maxKeypoints, scoreThreshold, inputSize.width, inputSize.height, backendId, targetId));
    }

    /**
     * Creates an XFeat detector.
     *     @param modelPath Path to the XFeat ONNX model.
     *     @param maxKeypoints Maximum number of keypoints to return per image. The strongest
     *                         responses are kept; -1 keeps all detections.
     *     @param scoreThreshold Discard keypoints with network score not greater than this value.
     *     @param inputSize Input size fed to the network, default Size(640, 640).
     *     @param backendId DNN backend identifier (see cv::dnn::Backend); 0 = DNN_BACKEND_DEFAULT.
     * @return automatically generated
     */
    public static XFeat create(String modelPath, int maxKeypoints, float scoreThreshold, Size inputSize, int backendId) {
        return XFeat.__fromPtr__(create_1(modelPath, maxKeypoints, scoreThreshold, inputSize.width, inputSize.height, backendId));
    }

    /**
     * Creates an XFeat detector.
     *     @param modelPath Path to the XFeat ONNX model.
     *     @param maxKeypoints Maximum number of keypoints to return per image. The strongest
     *                         responses are kept; -1 keeps all detections.
     *     @param scoreThreshold Discard keypoints with network score not greater than this value.
     *     @param inputSize Input size fed to the network, default Size(640, 640).
     * @return automatically generated
     */
    public static XFeat create(String modelPath, int maxKeypoints, float scoreThreshold, Size inputSize) {
        return XFeat.__fromPtr__(create_2(modelPath, maxKeypoints, scoreThreshold, inputSize.width, inputSize.height));
    }

    /**
     * Creates an XFeat detector.
     *     @param modelPath Path to the XFeat ONNX model.
     *     @param maxKeypoints Maximum number of keypoints to return per image. The strongest
     *                         responses are kept; -1 keeps all detections.
     *     @param scoreThreshold Discard keypoints with network score not greater than this value.
     * @return automatically generated
     */
    public static XFeat create(String modelPath, int maxKeypoints, float scoreThreshold) {
        return XFeat.__fromPtr__(create_3(modelPath, maxKeypoints, scoreThreshold));
    }

    /**
     * Creates an XFeat detector.
     *     @param modelPath Path to the XFeat ONNX model.
     *     @param maxKeypoints Maximum number of keypoints to return per image. The strongest
     *                         responses are kept; -1 keeps all detections.
     * @return automatically generated
     */
    public static XFeat create(String modelPath, int maxKeypoints) {
        return XFeat.__fromPtr__(create_4(modelPath, maxKeypoints));
    }

    /**
     * Creates an XFeat detector.
     *     @param modelPath Path to the XFeat ONNX model.
     *                         responses are kept; -1 keeps all detections.
     * @return automatically generated
     */
    public static XFeat create(String modelPath) {
        return XFeat.__fromPtr__(create_5(modelPath));
    }


    //
    // C++: static Ptr_XFeat cv::XFeat::create(vector_uchar bufferModel, int maxKeypoints = -1, float scoreThreshold = 0.5f, Size inputSize = Size(640, 640), int backendId = 0, int targetId = 0)
    //

    /**
     * Creates an XFeat detector from an in-memory model buffer.
     * @param bufferModel automatically generated
     * @param maxKeypoints automatically generated
     * @param scoreThreshold automatically generated
     * @param inputSize automatically generated
     * @param backendId automatically generated
     * @param targetId automatically generated
     * @return automatically generated
     */
    public static XFeat createFromMemory(MatOfByte bufferModel, int maxKeypoints, float scoreThreshold, Size inputSize, int backendId, int targetId) {
        Mat bufferModel_mat = bufferModel;
        return XFeat.__fromPtr__(createFromMemory_0(bufferModel_mat.nativeObj, maxKeypoints, scoreThreshold, inputSize.width, inputSize.height, backendId, targetId));
    }

    /**
     * Creates an XFeat detector from an in-memory model buffer.
     * @param bufferModel automatically generated
     * @param maxKeypoints automatically generated
     * @param scoreThreshold automatically generated
     * @param inputSize automatically generated
     * @param backendId automatically generated
     * @return automatically generated
     */
    public static XFeat createFromMemory(MatOfByte bufferModel, int maxKeypoints, float scoreThreshold, Size inputSize, int backendId) {
        Mat bufferModel_mat = bufferModel;
        return XFeat.__fromPtr__(createFromMemory_1(bufferModel_mat.nativeObj, maxKeypoints, scoreThreshold, inputSize.width, inputSize.height, backendId));
    }

    /**
     * Creates an XFeat detector from an in-memory model buffer.
     * @param bufferModel automatically generated
     * @param maxKeypoints automatically generated
     * @param scoreThreshold automatically generated
     * @param inputSize automatically generated
     * @return automatically generated
     */
    public static XFeat createFromMemory(MatOfByte bufferModel, int maxKeypoints, float scoreThreshold, Size inputSize) {
        Mat bufferModel_mat = bufferModel;
        return XFeat.__fromPtr__(createFromMemory_2(bufferModel_mat.nativeObj, maxKeypoints, scoreThreshold, inputSize.width, inputSize.height));
    }

    /**
     * Creates an XFeat detector from an in-memory model buffer.
     * @param bufferModel automatically generated
     * @param maxKeypoints automatically generated
     * @param scoreThreshold automatically generated
     * @return automatically generated
     */
    public static XFeat createFromMemory(MatOfByte bufferModel, int maxKeypoints, float scoreThreshold) {
        Mat bufferModel_mat = bufferModel;
        return XFeat.__fromPtr__(createFromMemory_3(bufferModel_mat.nativeObj, maxKeypoints, scoreThreshold));
    }

    /**
     * Creates an XFeat detector from an in-memory model buffer.
     * @param bufferModel automatically generated
     * @param maxKeypoints automatically generated
     * @return automatically generated
     */
    public static XFeat createFromMemory(MatOfByte bufferModel, int maxKeypoints) {
        Mat bufferModel_mat = bufferModel;
        return XFeat.__fromPtr__(createFromMemory_4(bufferModel_mat.nativeObj, maxKeypoints));
    }

    /**
     * Creates an XFeat detector from an in-memory model buffer.
     * @param bufferModel automatically generated
     * @return automatically generated
     */
    public static XFeat createFromMemory(MatOfByte bufferModel) {
        Mat bufferModel_mat = bufferModel;
        return XFeat.__fromPtr__(createFromMemory_5(bufferModel_mat.nativeObj));
    }


    //
    // C++:  void cv::XFeat::setMaxKeypoints(int maxKeypoints)
    //

    public void setMaxKeypoints(int maxKeypoints) {
        setMaxKeypoints_0(nativeObj, maxKeypoints);
    }


    //
    // C++:  int cv::XFeat::getMaxKeypoints()
    //

    public int getMaxKeypoints() {
        return getMaxKeypoints_0(nativeObj);
    }


    //
    // C++:  void cv::XFeat::setScoreThreshold(float threshold)
    //

    public void setScoreThreshold(float threshold) {
        setScoreThreshold_0(nativeObj, threshold);
    }


    //
    // C++:  float cv::XFeat::getScoreThreshold()
    //

    public float getScoreThreshold() {
        return getScoreThreshold_0(nativeObj);
    }


    //
    // C++:  void cv::XFeat::setInputSize(Size inputSize)
    //

    public void setInputSize(Size inputSize) {
        setInputSize_0(nativeObj, inputSize.width, inputSize.height);
    }


    //
    // C++:  Size cv::XFeat::getInputSize()
    //

    public Size getInputSize() {
        return new Size(getInputSize_0(nativeObj));
    }


    //
    // C++:  String cv::XFeat::getDefaultName()
    //

    public String getDefaultName() {
        return getDefaultName_0(nativeObj);
    }


    @Override
    protected void finalize() throws Throwable {
        delete(nativeObj);
    }



    // C++: static Ptr_XFeat cv::XFeat::create(String modelPath, int maxKeypoints = -1, float scoreThreshold = 0.5f, Size inputSize = Size(640, 640), int backendId = 0, int targetId = 0)
    private static native long create_0(String modelPath, int maxKeypoints, float scoreThreshold, double inputSize_width, double inputSize_height, int backendId, int targetId);
    private static native long create_1(String modelPath, int maxKeypoints, float scoreThreshold, double inputSize_width, double inputSize_height, int backendId);
    private static native long create_2(String modelPath, int maxKeypoints, float scoreThreshold, double inputSize_width, double inputSize_height);
    private static native long create_3(String modelPath, int maxKeypoints, float scoreThreshold);
    private static native long create_4(String modelPath, int maxKeypoints);
    private static native long create_5(String modelPath);

    // C++: static Ptr_XFeat cv::XFeat::create(vector_uchar bufferModel, int maxKeypoints = -1, float scoreThreshold = 0.5f, Size inputSize = Size(640, 640), int backendId = 0, int targetId = 0)
    private static native long createFromMemory_0(long bufferModel_mat_nativeObj, int maxKeypoints, float scoreThreshold, double inputSize_width, double inputSize_height, int backendId, int targetId);
    private static native long createFromMemory_1(long bufferModel_mat_nativeObj, int maxKeypoints, float scoreThreshold, double inputSize_width, double inputSize_height, int backendId);
    private static native long createFromMemory_2(long bufferModel_mat_nativeObj, int maxKeypoints, float scoreThreshold, double inputSize_width, double inputSize_height);
    private static native long createFromMemory_3(long bufferModel_mat_nativeObj, int maxKeypoints, float scoreThreshold);
    private static native long createFromMemory_4(long bufferModel_mat_nativeObj, int maxKeypoints);
    private static native long createFromMemory_5(long bufferModel_mat_nativeObj);

    // C++:  void cv::XFeat::setMaxKeypoints(int maxKeypoints)
    private static native void setMaxKeypoints_0(long nativeObj, int maxKeypoints);

    // C++:  int cv::XFeat::getMaxKeypoints()
    private static native int getMaxKeypoints_0(long nativeObj);

    // C++:  void cv::XFeat::setScoreThreshold(float threshold)
    private static native void setScoreThreshold_0(long nativeObj, float threshold);

    // C++:  float cv::XFeat::getScoreThreshold()
    private static native float getScoreThreshold_0(long nativeObj);

    // C++:  void cv::XFeat::setInputSize(Size inputSize)
    private static native void setInputSize_0(long nativeObj, double inputSize_width, double inputSize_height);

    // C++:  Size cv::XFeat::getInputSize()
    private static native double[] getInputSize_0(long nativeObj);

    // C++:  String cv::XFeat::getDefaultName()
    private static native String getDefaultName_0(long nativeObj);

    // native support for java finalize() or cleaner
    private static native void delete(long nativeObj);

}
