//
// This file is auto-generated. Please don't modify it!
//
package org.opencv.dnn;

import java.util.ArrayList;
import java.util.List;
import org.opencv.core.Mat;
import org.opencv.dnn.LayerInfo;
import org.opencv.utils.Converters;

// C++: class Layer
/**
 * This interface class allows to build new Layers - are building blocks of networks.
 *
 * A %Layer is the *executable*, backend-specific counterpart of a LayerInfo node: it
 * implements forward() (and finalize()) for a particular backend/target. In the new graph
 * engine a %Layer is created from a LayerInfo by the executor factory; shape/type inference
 * stays on the LayerInfo node.
 *
 * Each class, derived from Layer, must implement forward() method to compute outputs.
 * Also before using the new layer into networks you must register your layer by using one of REF: dnnLayerFactory "LayerFactory" macros.
 */
public class Layer extends LayerInfo {

    protected Layer(long addr) { super(addr); }

    // internal usage only
    public static Layer __fromPtr__(long addr) { return new Layer(addr); }

    //
    // C++:  void cv::dnn::Layer::finalize(vector_Mat inputs, vector_Mat& outputs)
    //

    /**
     * Computes and sets internal parameters according to inputs, outputs and blobs.
     * @param outputs vector of already allocated output blobs
     *
     * This method is called after network has allocated all memory for input and output blobs
     * and before inferencing.
     * @param inputs automatically generated
     */
    public void finalize(List<Mat> inputs, List<Mat> outputs) {
        Mat inputs_mat = Converters.vector_Mat_to_Mat(inputs);
        Mat outputs_mat = new Mat();
        finalize_0(nativeObj, inputs_mat.nativeObj, outputs_mat.nativeObj);
        Converters.Mat_to_vector_Mat(outputs_mat, outputs);
        outputs_mat.release();
    }


    //
    // C++:  void cv::dnn::Layer::run(vector_Mat inputs, vector_Mat& outputs, vector_Mat& internals)
    //

    /**
     * Allocates layer and computes output.
     * @deprecated This method will be removed in the future release.
     * @param inputs automatically generated
     * @param outputs automatically generated
     * @param internals automatically generated
     */
    @Deprecated
    public void run(List<Mat> inputs, List<Mat> outputs, List<Mat> internals) {
        Mat inputs_mat = Converters.vector_Mat_to_Mat(inputs);
        Mat outputs_mat = new Mat();
        Mat internals_mat = Converters.vector_Mat_to_Mat(internals);
        run_0(nativeObj, inputs_mat.nativeObj, outputs_mat.nativeObj, internals_mat.nativeObj);
        Converters.Mat_to_vector_Mat(outputs_mat, outputs);
        outputs_mat.release();
        Converters.Mat_to_vector_Mat(internals_mat, internals);
        internals_mat.release();
    }


    //
    // C++: int Layer::preferableTarget
    //

    public int get_preferableTarget() {
        return get_preferableTarget_0(nativeObj);
    }


    @Override
    protected void finalize() throws Throwable {
        delete(nativeObj);
    }



    // C++:  void cv::dnn::Layer::finalize(vector_Mat inputs, vector_Mat& outputs)
    private static native void finalize_0(long nativeObj, long inputs_mat_nativeObj, long outputs_mat_nativeObj);

    // C++:  void cv::dnn::Layer::run(vector_Mat inputs, vector_Mat& outputs, vector_Mat& internals)
    private static native void run_0(long nativeObj, long inputs_mat_nativeObj, long outputs_mat_nativeObj, long internals_mat_nativeObj);

    // C++: int Layer::preferableTarget
    private static native int get_preferableTarget_0(long nativeObj);

    // native support for java finalize() or cleaner
    private static native void delete(long nativeObj);

}
