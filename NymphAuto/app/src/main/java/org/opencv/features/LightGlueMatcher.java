//
// This file is auto-generated. Please don't modify it!
//
package org.opencv.features;

import java.util.ArrayList;
import java.util.List;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.Size;
import org.opencv.features.DescriptorMatcher;
import org.opencv.features.LightGlueMatcher;
import org.opencv.utils.Converters;

// C++: class LightGlueMatcher
/**
 * LightGlue feature matcher.
 *
 * LightGlue is an attention-based feature matcher, as described in CITE: Lindenberger23 . It takes
 * keypoint locations and descriptors from two images and directly predicts match pairs. Unlike
 * traditional matchers that compute descriptor distances, LightGlue produces confidence scores
 * for each potential match pair.
 *
 * The matcher extends DescriptorMatcher and supports match() and knnMatch() with k=1.
 * Context (keypoints and image sizes) must be provided via setPairInfo() or setImagePairInfo()
 * before matching. radiusMatch() is not supported.
 */
public class LightGlueMatcher extends DescriptorMatcher {

    protected LightGlueMatcher(long addr) { super(addr); }

    // internal usage only
    public static LightGlueMatcher __fromPtr__(long addr) { return new LightGlueMatcher(addr); }

    //
    // C++: static Ptr_LightGlueMatcher cv::LightGlueMatcher::create(String modelPath, float scoreThreshold = 0.0f, int backend = 0, int target = 0, int type = LG_ALIKED)
    //

    /**
     * Creates LightGlueMatcher from a model file path.
     *     @param modelPath Path to the ONNX model file.
     *     @param scoreThreshold Match confidence threshold.
     *     @param backend DNN backend
     *     @param target DNN target
     *     @param type Model variant: LG_ALIKED (default) or LG_DISK.
     * @return automatically generated
     */
    public static LightGlueMatcher create(String modelPath, float scoreThreshold, int backend, int target, int type) {
        return LightGlueMatcher.__fromPtr__(create_0(modelPath, scoreThreshold, backend, target, type));
    }

    /**
     * Creates LightGlueMatcher from a model file path.
     *     @param modelPath Path to the ONNX model file.
     *     @param scoreThreshold Match confidence threshold.
     *     @param backend DNN backend
     *     @param target DNN target
     * @return automatically generated
     */
    public static LightGlueMatcher create(String modelPath, float scoreThreshold, int backend, int target) {
        return LightGlueMatcher.__fromPtr__(create_1(modelPath, scoreThreshold, backend, target));
    }

    /**
     * Creates LightGlueMatcher from a model file path.
     *     @param modelPath Path to the ONNX model file.
     *     @param scoreThreshold Match confidence threshold.
     *     @param backend DNN backend
     * @return automatically generated
     */
    public static LightGlueMatcher create(String modelPath, float scoreThreshold, int backend) {
        return LightGlueMatcher.__fromPtr__(create_2(modelPath, scoreThreshold, backend));
    }

    /**
     * Creates LightGlueMatcher from a model file path.
     *     @param modelPath Path to the ONNX model file.
     *     @param scoreThreshold Match confidence threshold.
     * @return automatically generated
     */
    public static LightGlueMatcher create(String modelPath, float scoreThreshold) {
        return LightGlueMatcher.__fromPtr__(create_3(modelPath, scoreThreshold));
    }

    /**
     * Creates LightGlueMatcher from a model file path.
     *     @param modelPath Path to the ONNX model file.
     * @return automatically generated
     */
    public static LightGlueMatcher create(String modelPath) {
        return LightGlueMatcher.__fromPtr__(create_4(modelPath));
    }


    //
    // C++: static Ptr_LightGlueMatcher cv::LightGlueMatcher::create(vector_uchar modelData, float scoreThreshold = 0.0f, int backend = 0, int target = 0, int type = LG_ALIKED)
    //

    /**
     * Creates LightGlueMatcher from in-memory model data.
     *     @param modelData Buffer containing the model data.
     *     @param scoreThreshold Match confidence threshold.
     *     @param backend DNN backend
     *     @param target DNN target
     *     @param type Model variant: LG_ALIKED (default) or LG_DISK.
     * @return automatically generated
     */
    public static LightGlueMatcher createFromMemory(MatOfByte modelData, float scoreThreshold, int backend, int target, int type) {
        Mat modelData_mat = modelData;
        return LightGlueMatcher.__fromPtr__(createFromMemory_0(modelData_mat.nativeObj, scoreThreshold, backend, target, type));
    }

    /**
     * Creates LightGlueMatcher from in-memory model data.
     *     @param modelData Buffer containing the model data.
     *     @param scoreThreshold Match confidence threshold.
     *     @param backend DNN backend
     *     @param target DNN target
     * @return automatically generated
     */
    public static LightGlueMatcher createFromMemory(MatOfByte modelData, float scoreThreshold, int backend, int target) {
        Mat modelData_mat = modelData;
        return LightGlueMatcher.__fromPtr__(createFromMemory_1(modelData_mat.nativeObj, scoreThreshold, backend, target));
    }

    /**
     * Creates LightGlueMatcher from in-memory model data.
     *     @param modelData Buffer containing the model data.
     *     @param scoreThreshold Match confidence threshold.
     *     @param backend DNN backend
     * @return automatically generated
     */
    public static LightGlueMatcher createFromMemory(MatOfByte modelData, float scoreThreshold, int backend) {
        Mat modelData_mat = modelData;
        return LightGlueMatcher.__fromPtr__(createFromMemory_2(modelData_mat.nativeObj, scoreThreshold, backend));
    }

    /**
     * Creates LightGlueMatcher from in-memory model data.
     *     @param modelData Buffer containing the model data.
     *     @param scoreThreshold Match confidence threshold.
     * @return automatically generated
     */
    public static LightGlueMatcher createFromMemory(MatOfByte modelData, float scoreThreshold) {
        Mat modelData_mat = modelData;
        return LightGlueMatcher.__fromPtr__(createFromMemory_3(modelData_mat.nativeObj, scoreThreshold));
    }

    /**
     * Creates LightGlueMatcher from in-memory model data.
     *     @param modelData Buffer containing the model data.
     * @return automatically generated
     */
    public static LightGlueMatcher createFromMemory(MatOfByte modelData) {
        Mat modelData_mat = modelData;
        return LightGlueMatcher.__fromPtr__(createFromMemory_4(modelData_mat.nativeObj));
    }


    //
    // C++:  void cv::LightGlueMatcher::setPairInfo(Mat queryKpts, Mat trainKpts, Size queryImageSize = Size(), Size trainImageSize = Size())
    //

    /**
     * Sets the keypoint and image size context for the next match() call.
     *
     *     This provides the spatial context that LightGlue needs in addition to descriptors.
     *     Must be called before match()/knnMatch(), or use setImagePairInfo().
     *     Pixel coordinates are normalized according to the LightGlueType selected at creation, or as
     *     selected by setKeypointNormalization().
     *     For keypoints already normalized for that model, leave the corresponding image size empty.
     *
     *     @param queryKpts Query image keypoints (Nx2 float matrix with x,y coordinates).
     *     @param trainKpts Train image keypoints (Nx2 float matrix with x,y coordinates).
     *     @param queryImageSize Size of the query image, or Size() for normalized query keypoints.
     *     @param trainImageSize Size of the train image, or Size() for normalized train keypoints.
     */
    public void setPairInfo(Mat queryKpts, Mat trainKpts, Size queryImageSize, Size trainImageSize) {
        setPairInfo_0(nativeObj, queryKpts.nativeObj, trainKpts.nativeObj, queryImageSize.width, queryImageSize.height, trainImageSize.width, trainImageSize.height);
    }

    /**
     * Sets the keypoint and image size context for the next match() call.
     *
     *     This provides the spatial context that LightGlue needs in addition to descriptors.
     *     Must be called before match()/knnMatch(), or use setImagePairInfo().
     *     Pixel coordinates are normalized according to the LightGlueType selected at creation, or as
     *     selected by setKeypointNormalization().
     *     For keypoints already normalized for that model, leave the corresponding image size empty.
     *
     *     @param queryKpts Query image keypoints (Nx2 float matrix with x,y coordinates).
     *     @param trainKpts Train image keypoints (Nx2 float matrix with x,y coordinates).
     *     @param queryImageSize Size of the query image, or Size() for normalized query keypoints.
     */
    public void setPairInfo(Mat queryKpts, Mat trainKpts, Size queryImageSize) {
        setPairInfo_1(nativeObj, queryKpts.nativeObj, trainKpts.nativeObj, queryImageSize.width, queryImageSize.height);
    }

    /**
     * Sets the keypoint and image size context for the next match() call.
     *
     *     This provides the spatial context that LightGlue needs in addition to descriptors.
     *     Must be called before match()/knnMatch(), or use setImagePairInfo().
     *     Pixel coordinates are normalized according to the LightGlueType selected at creation, or as
     *     selected by setKeypointNormalization().
     *     For keypoints already normalized for that model, leave the corresponding image size empty.
     *
     *     @param queryKpts Query image keypoints (Nx2 float matrix with x,y coordinates).
     *     @param trainKpts Train image keypoints (Nx2 float matrix with x,y coordinates).
     */
    public void setPairInfo(Mat queryKpts, Mat trainKpts) {
        setPairInfo_2(nativeObj, queryKpts.nativeObj, trainKpts.nativeObj);
    }


    //
    // C++:  void cv::LightGlueMatcher::clearPairInfo()
    //

    /**
     * Clears stored pair context information.
     */
    public void clearPairInfo() {
        clearPairInfo_0(nativeObj);
    }


    //
    // C++:  void cv::LightGlueMatcher::setKeypointNormalization(int normalization)
    //

    /**
     * Selects how pixel keypoints passed with an image size are normalized.
     *
     *     The default, LG_NORM_MODEL, keeps the per-axis mapping implied by the LightGlueType given at
     *     creation. LG_NORM_MAX_SIDE applies the mapping of the LightGlue reference implementation, which
     *     shifts by half the image size and divides both axes by half the longer side. Use it for models
     *     exported from the reference forward without their own normalization, e.g. XFeat's LighterGlue.
     *     For square images the two mappings coincide for LG_ALIKED.
     *
     *     The setting does not affect keypoints passed with an empty image size, and it is kept by clone().
     *     @param normalization One of LightGlueNormalization.
     */
    public void setKeypointNormalization(int normalization) {
        setKeypointNormalization_0(nativeObj, normalization);
    }


    //
    // C++:  int cv::LightGlueMatcher::getKeypointNormalization()
    //

    /**
     * Returns the keypoint normalization, one of LightGlueNormalization.
     * @return automatically generated
     */
    public int getKeypointNormalization() {
        return getKeypointNormalization_0(nativeObj);
    }


    //
    // C++:  void cv::LightGlueMatcher::setImagePairInfo(vector_KeyPoint queryKpts, vector_KeyPoint trainKpts, Size queryImageSize = Size(), Size trainImageSize = Size())
    //

    /**
     * Convenience overload of setPairInfo() taking keypoints directly.
     *     Uses the same model-specific normalization and image size convention as setPairInfo().
     * @param queryKpts automatically generated
     * @param trainKpts automatically generated
     * @param queryImageSize automatically generated
     * @param trainImageSize automatically generated
     */
    public void setImagePairInfo(MatOfKeyPoint queryKpts, MatOfKeyPoint trainKpts, Size queryImageSize, Size trainImageSize) {
        Mat queryKpts_mat = queryKpts;
        Mat trainKpts_mat = trainKpts;
        setImagePairInfo_0(nativeObj, queryKpts_mat.nativeObj, trainKpts_mat.nativeObj, queryImageSize.width, queryImageSize.height, trainImageSize.width, trainImageSize.height);
    }

    /**
     * Convenience overload of setPairInfo() taking keypoints directly.
     *     Uses the same model-specific normalization and image size convention as setPairInfo().
     * @param queryKpts automatically generated
     * @param trainKpts automatically generated
     * @param queryImageSize automatically generated
     */
    public void setImagePairInfo(MatOfKeyPoint queryKpts, MatOfKeyPoint trainKpts, Size queryImageSize) {
        Mat queryKpts_mat = queryKpts;
        Mat trainKpts_mat = trainKpts;
        setImagePairInfo_1(nativeObj, queryKpts_mat.nativeObj, trainKpts_mat.nativeObj, queryImageSize.width, queryImageSize.height);
    }

    /**
     * Convenience overload of setPairInfo() taking keypoints directly.
     *     Uses the same model-specific normalization and image size convention as setPairInfo().
     * @param queryKpts automatically generated
     * @param trainKpts automatically generated
     */
    public void setImagePairInfo(MatOfKeyPoint queryKpts, MatOfKeyPoint trainKpts) {
        Mat queryKpts_mat = queryKpts;
        Mat trainKpts_mat = trainKpts;
        setImagePairInfo_2(nativeObj, queryKpts_mat.nativeObj, trainKpts_mat.nativeObj);
    }


    @Override
    protected void finalize() throws Throwable {
        delete(nativeObj);
    }



    // C++: static Ptr_LightGlueMatcher cv::LightGlueMatcher::create(String modelPath, float scoreThreshold = 0.0f, int backend = 0, int target = 0, int type = LG_ALIKED)
    private static native long create_0(String modelPath, float scoreThreshold, int backend, int target, int type);
    private static native long create_1(String modelPath, float scoreThreshold, int backend, int target);
    private static native long create_2(String modelPath, float scoreThreshold, int backend);
    private static native long create_3(String modelPath, float scoreThreshold);
    private static native long create_4(String modelPath);

    // C++: static Ptr_LightGlueMatcher cv::LightGlueMatcher::create(vector_uchar modelData, float scoreThreshold = 0.0f, int backend = 0, int target = 0, int type = LG_ALIKED)
    private static native long createFromMemory_0(long modelData_mat_nativeObj, float scoreThreshold, int backend, int target, int type);
    private static native long createFromMemory_1(long modelData_mat_nativeObj, float scoreThreshold, int backend, int target);
    private static native long createFromMemory_2(long modelData_mat_nativeObj, float scoreThreshold, int backend);
    private static native long createFromMemory_3(long modelData_mat_nativeObj, float scoreThreshold);
    private static native long createFromMemory_4(long modelData_mat_nativeObj);

    // C++:  void cv::LightGlueMatcher::setPairInfo(Mat queryKpts, Mat trainKpts, Size queryImageSize = Size(), Size trainImageSize = Size())
    private static native void setPairInfo_0(long nativeObj, long queryKpts_nativeObj, long trainKpts_nativeObj, double queryImageSize_width, double queryImageSize_height, double trainImageSize_width, double trainImageSize_height);
    private static native void setPairInfo_1(long nativeObj, long queryKpts_nativeObj, long trainKpts_nativeObj, double queryImageSize_width, double queryImageSize_height);
    private static native void setPairInfo_2(long nativeObj, long queryKpts_nativeObj, long trainKpts_nativeObj);

    // C++:  void cv::LightGlueMatcher::clearPairInfo()
    private static native void clearPairInfo_0(long nativeObj);

    // C++:  void cv::LightGlueMatcher::setKeypointNormalization(int normalization)
    private static native void setKeypointNormalization_0(long nativeObj, int normalization);

    // C++:  int cv::LightGlueMatcher::getKeypointNormalization()
    private static native int getKeypointNormalization_0(long nativeObj);

    // C++:  void cv::LightGlueMatcher::setImagePairInfo(vector_KeyPoint queryKpts, vector_KeyPoint trainKpts, Size queryImageSize = Size(), Size trainImageSize = Size())
    private static native void setImagePairInfo_0(long nativeObj, long queryKpts_mat_nativeObj, long trainKpts_mat_nativeObj, double queryImageSize_width, double queryImageSize_height, double trainImageSize_width, double trainImageSize_height);
    private static native void setImagePairInfo_1(long nativeObj, long queryKpts_mat_nativeObj, long trainKpts_mat_nativeObj, double queryImageSize_width, double queryImageSize_height);
    private static native void setImagePairInfo_2(long nativeObj, long queryKpts_mat_nativeObj, long trainKpts_mat_nativeObj);

    // native support for java finalize() or cleaner
    private static native void delete(long nativeObj);

}
