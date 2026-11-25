//
// This file is auto-generated. Please don't modify it!
//
package org.opencv.core;

import java.util.ArrayList;
import java.util.List;
import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.utils.Converters;

// C++: class ReduceParams
/**
 * The reduction that the n-dimensional #reduce performs.
 *
 * Everything the call needs besides the input and the output arrays, in one struct, so that further
 * options can be added without changing the signature.
 *
 * SEE: reduce
 */
public class ReduceParams {

    protected final long nativeObj;
    protected ReduceParams(long addr) {
      nativeObj = addr;
      
    }

    public long getNativeObjAddr() { return nativeObj; }

    // internal usage only
    public static ReduceParams __fromPtr__(long addr) { return new ReduceParams(addr); }

    //
    // C++:   cv::ReduceParams::ReduceParams(int rtype = REDUCE_SUM, vector_int axes = std::vector<int>(), bool keepdims = true, int dtype = -1)
    //

    public ReduceParams(int rtype, MatOfInt axes, boolean keepdims, int dtype) {
        Mat axes_mat = axes;
        nativeObj = ReduceParams_0(rtype, axes_mat.nativeObj, keepdims, dtype);
    }

    public ReduceParams(int rtype, MatOfInt axes, boolean keepdims) {
        Mat axes_mat = axes;
        nativeObj = ReduceParams_1(rtype, axes_mat.nativeObj, keepdims);
    }

    public ReduceParams(int rtype, MatOfInt axes) {
        Mat axes_mat = axes;
        nativeObj = ReduceParams_2(rtype, axes_mat.nativeObj);
    }

    public ReduceParams(int rtype) {
        nativeObj = ReduceParams_3(rtype);
    }

    public ReduceParams() {
        nativeObj = ReduceParams_4();
    }


    //
    // C++: int ReduceParams::rtype
    //

    public int get_rtype() {
        return get_rtype_0(nativeObj);
    }


    //
    // C++: void ReduceParams::rtype
    //

    public void set_rtype(int rtype) {
        set_rtype_0(nativeObj, rtype);
    }


    //
    // C++: vector_int ReduceParams::axes
    //

    public MatOfInt get_axes() {
        return MatOfInt.fromNativeAddr(get_axes_0(nativeObj));
    }


    //
    // C++: void ReduceParams::axes
    //

    public void set_axes(MatOfInt axes) {
        Mat axes_mat = axes;
        set_axes_0(nativeObj, axes_mat.nativeObj);
    }


    //
    // C++: bool ReduceParams::keepdims
    //

    public boolean get_keepdims() {
        return get_keepdims_0(nativeObj);
    }


    //
    // C++: void ReduceParams::keepdims
    //

    public void set_keepdims(boolean keepdims) {
        set_keepdims_0(nativeObj, keepdims);
    }


    //
    // C++: int ReduceParams::dtype
    //

    public int get_dtype() {
        return get_dtype_0(nativeObj);
    }


    //
    // C++: void ReduceParams::dtype
    //

    public void set_dtype(int dtype) {
        set_dtype_0(nativeObj, dtype);
    }


    @Override
    protected void finalize() throws Throwable {
        delete(nativeObj);
    }



    // C++:   cv::ReduceParams::ReduceParams(int rtype = REDUCE_SUM, vector_int axes = std::vector<int>(), bool keepdims = true, int dtype = -1)
    private static native long ReduceParams_0(int rtype, long axes_mat_nativeObj, boolean keepdims, int dtype);
    private static native long ReduceParams_1(int rtype, long axes_mat_nativeObj, boolean keepdims);
    private static native long ReduceParams_2(int rtype, long axes_mat_nativeObj);
    private static native long ReduceParams_3(int rtype);
    private static native long ReduceParams_4();

    // C++: int ReduceParams::rtype
    private static native int get_rtype_0(long nativeObj);

    // C++: void ReduceParams::rtype
    private static native void set_rtype_0(long nativeObj, int rtype);

    // C++: vector_int ReduceParams::axes
    private static native long get_axes_0(long nativeObj);

    // C++: void ReduceParams::axes
    private static native void set_axes_0(long nativeObj, long axes_mat_nativeObj);

    // C++: bool ReduceParams::keepdims
    private static native boolean get_keepdims_0(long nativeObj);

    // C++: void ReduceParams::keepdims
    private static native void set_keepdims_0(long nativeObj, boolean keepdims);

    // C++: int ReduceParams::dtype
    private static native int get_dtype_0(long nativeObj);

    // C++: void ReduceParams::dtype
    private static native void set_dtype_0(long nativeObj, int dtype);

    // native support for java finalize() or cleaner
    private static native void delete(long nativeObj);

}
