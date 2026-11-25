//
// This file is auto-generated. Please don't modify it!
//
package org.opencv.dnn;

import java.util.ArrayList;
import java.util.List;
import org.opencv.core.Mat;
import org.opencv.utils.Converters;

// C++: class LayerInfo
/**
 * Backend-independent description of a graph operation (node).
 *
 * %LayerInfo carries everything needed to reason about an operation *without* executing it:
 * its parameters (#blobs and type-specific fields of derived classes), graph wiring
 * (#inputs / #outputs as Arg indices) and shape/type/layout inference. The new DNN graph
 * engine stores a topologically sorted sequence of %LayerInfo nodes (see Graph::prog());
 * executable, backend-specific instances (Layer subclasses) are constructed from an
 * %LayerInfo during Net::finalizeNet().
 *
 * Each operation type registers a {@code static Ptr&lt;LayerInfo&gt; create(const LayerParams&amp;)} factory
 * via REF: CV_DNN_REGISTER_OP_CLASS.
 */
public class LayerInfo {

    protected final long nativeObj;
    protected LayerInfo(long addr) {
      nativeObj = addr;
      
    }

    public long getNativeObjAddr() { return nativeObj; }

    // internal usage only
    public static LayerInfo __fromPtr__(long addr) { return new LayerInfo(addr); }

    //
    // C++:  int cv::dnn::LayerInfo::outputNameToIndex(String outputName)
    //

    public int outputNameToIndex(String outputName) {
        return outputNameToIndex_0(nativeObj, outputName);
    }


    //
    // C++: vector_Mat LayerInfo::blobs
    //

    public List<Mat> get_blobs() {
        List<Mat> retVal = new ArrayList<Mat>();
        Mat retValMat = new Mat(get_blobs_0(nativeObj));
        Converters.Mat_to_vector_Mat(retValMat, retVal);
        return retVal;
    }


    //
    // C++: void LayerInfo::blobs
    //

    public void set_blobs(List<Mat> blobs) {
        Mat blobs_mat = Converters.vector_Mat_to_Mat(blobs);
        set_blobs_0(nativeObj, blobs_mat.nativeObj);
    }


    //
    // C++: String LayerInfo::name
    //

    public String get_name() {
        return get_name_0(nativeObj);
    }


    //
    // C++: String LayerInfo::type
    //

    public String get_type() {
        return get_type_0(nativeObj);
    }


    @Override
    protected void finalize() throws Throwable {
        delete(nativeObj);
    }



    // C++:  int cv::dnn::LayerInfo::outputNameToIndex(String outputName)
    private static native int outputNameToIndex_0(long nativeObj, String outputName);

    // C++: vector_Mat LayerInfo::blobs
    private static native long get_blobs_0(long nativeObj);

    // C++: void LayerInfo::blobs
    private static native void set_blobs_0(long nativeObj, long blobs_mat_nativeObj);

    // C++: String LayerInfo::name
    private static native String get_name_0(long nativeObj);

    // C++: String LayerInfo::type
    private static native String get_type_0(long nativeObj);

    // native support for java finalize() or cleaner
    private static native void delete(long nativeObj);

}
