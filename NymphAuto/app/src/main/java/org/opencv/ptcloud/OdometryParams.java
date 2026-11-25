//
// This file is auto-generated. Please don't modify it!
//
package org.opencv.ptcloud;



// C++: class OdometryParams
/**
 * Tunable parameters for visual odometry: initialization, tracking, keyframe selection, and local-map refinement.
 */
public class OdometryParams {

    protected final long nativeObj;
    protected OdometryParams(long addr) {
      nativeObj = addr;
      
    }

    public long getNativeObjAddr() { return nativeObj; }

    // internal usage only
    public static OdometryParams __fromPtr__(long addr) { return new OdometryParams(addr); }

    //
    // C++:   cv::slam::OdometryParams::OdometryParams()
    //

    public OdometryParams() {
        nativeObj = OdometryParams_0();
    }


    //
    // C++: int OdometryParams::minInitInliers
    //

    public int get_minInitInliers() {
        return get_minInitInliers_0(nativeObj);
    }


    //
    // C++: void OdometryParams::minInitInliers
    //

    public void set_minInitInliers(int minInitInliers) {
        set_minInitInliers_0(nativeObj, minInitInliers);
    }


    //
    // C++: double OdometryParams::minInitParallaxDeg
    //

    public double get_minInitParallaxDeg() {
        return get_minInitParallaxDeg_0(nativeObj);
    }


    //
    // C++: void OdometryParams::minInitParallaxDeg
    //

    public void set_minInitParallaxDeg(double minInitParallaxDeg) {
        set_minInitParallaxDeg_0(nativeObj, minInitParallaxDeg);
    }


    //
    // C++: int OdometryParams::minInitPoints
    //

    public int get_minInitPoints() {
        return get_minInitPoints_0(nativeObj);
    }


    //
    // C++: void OdometryParams::minInitPoints
    //

    public void set_minInitPoints(int minInitPoints) {
        set_minInitPoints_0(nativeObj, minInitPoints);
    }


    //
    // C++: double OdometryParams::hfRatioThresh
    //

    public double get_hfRatioThresh() {
        return get_hfRatioThresh_0(nativeObj);
    }


    //
    // C++: void OdometryParams::hfRatioThresh
    //

    public void set_hfRatioThresh(double hfRatioThresh) {
        set_hfRatioThresh_0(nativeObj, hfRatioThresh);
    }


    //
    // C++: double OdometryParams::minGrowthParallaxDeg
    //

    public double get_minGrowthParallaxDeg() {
        return get_minGrowthParallaxDeg_0(nativeObj);
    }


    //
    // C++: void OdometryParams::minGrowthParallaxDeg
    //

    public void set_minGrowthParallaxDeg(double minGrowthParallaxDeg) {
        set_minGrowthParallaxDeg_0(nativeObj, minGrowthParallaxDeg);
    }


    //
    // C++: double OdometryParams::essentialRansacThresh
    //

    public double get_essentialRansacThresh() {
        return get_essentialRansacThresh_0(nativeObj);
    }


    //
    // C++: void OdometryParams::essentialRansacThresh
    //

    public void set_essentialRansacThresh(double essentialRansacThresh) {
        set_essentialRansacThresh_0(nativeObj, essentialRansacThresh);
    }


    //
    // C++: double OdometryParams::essentialRansacConfidence
    //

    public double get_essentialRansacConfidence() {
        return get_essentialRansacConfidence_0(nativeObj);
    }


    //
    // C++: void OdometryParams::essentialRansacConfidence
    //

    public void set_essentialRansacConfidence(double essentialRansacConfidence) {
        set_essentialRansacConfidence_0(nativeObj, essentialRansacConfidence);
    }


    //
    // C++: double OdometryParams::pnpReprojThresh
    //

    public double get_pnpReprojThresh() {
        return get_pnpReprojThresh_0(nativeObj);
    }


    //
    // C++: void OdometryParams::pnpReprojThresh
    //

    public void set_pnpReprojThresh(double pnpReprojThresh) {
        set_pnpReprojThresh_0(nativeObj, pnpReprojThresh);
    }


    //
    // C++: int OdometryParams::pnpMinInliers
    //

    public int get_pnpMinInliers() {
        return get_pnpMinInliers_0(nativeObj);
    }


    //
    // C++: void OdometryParams::pnpMinInliers
    //

    public void set_pnpMinInliers(int pnpMinInliers) {
        set_pnpMinInliers_0(nativeObj, pnpMinInliers);
    }


    //
    // C++: int OdometryParams::pnpRansacIters
    //

    public int get_pnpRansacIters() {
        return get_pnpRansacIters_0(nativeObj);
    }


    //
    // C++: void OdometryParams::pnpRansacIters
    //

    public void set_pnpRansacIters(int pnpRansacIters) {
        set_pnpRansacIters_0(nativeObj, pnpRansacIters);
    }


    //
    // C++: double OdometryParams::pnpConfidence
    //

    public double get_pnpConfidence() {
        return get_pnpConfidence_0(nativeObj);
    }


    //
    // C++: void OdometryParams::pnpConfidence
    //

    public void set_pnpConfidence(double pnpConfidence) {
        set_pnpConfidence_0(nativeObj, pnpConfidence);
    }


    //
    // C++: double OdometryParams::motionModelRadius
    //

    public double get_motionModelRadius() {
        return get_motionModelRadius_0(nativeObj);
    }


    //
    // C++: void OdometryParams::motionModelRadius
    //

    public void set_motionModelRadius(double motionModelRadius) {
        set_motionModelRadius_0(nativeObj, motionModelRadius);
    }


    //
    // C++: double OdometryParams::motionModelRadiusWide
    //

    public double get_motionModelRadiusWide() {
        return get_motionModelRadiusWide_0(nativeObj);
    }


    //
    // C++: void OdometryParams::motionModelRadiusWide
    //

    public void set_motionModelRadiusWide(double motionModelRadiusWide) {
        set_motionModelRadiusWide_0(nativeObj, motionModelRadiusWide);
    }


    //
    // C++: int OdometryParams::motionModelMinMatches
    //

    public int get_motionModelMinMatches() {
        return get_motionModelMinMatches_0(nativeObj);
    }


    //
    // C++: void OdometryParams::motionModelMinMatches
    //

    public void set_motionModelMinMatches(int motionModelMinMatches) {
        set_motionModelMinMatches_0(nativeObj, motionModelMinMatches);
    }


    //
    // C++: double OdometryParams::descProjThresh
    //

    public double get_descProjThresh() {
        return get_descProjThresh_0(nativeObj);
    }


    //
    // C++: void OdometryParams::descProjThresh
    //

    public void set_descProjThresh(double descProjThresh) {
        set_descProjThresh_0(nativeObj, descProjThresh);
    }


    //
    // C++: int OdometryParams::opticalFlowMinInliers
    //

    public int get_opticalFlowMinInliers() {
        return get_opticalFlowMinInliers_0(nativeObj);
    }


    //
    // C++: void OdometryParams::opticalFlowMinInliers
    //

    public void set_opticalFlowMinInliers(int opticalFlowMinInliers) {
        set_opticalFlowMinInliers_0(nativeObj, opticalFlowMinInliers);
    }


    //
    // C++: int OdometryParams::kfMinFrames
    //

    public int get_kfMinFrames() {
        return get_kfMinFrames_0(nativeObj);
    }


    //
    // C++: void OdometryParams::kfMinFrames
    //

    public void set_kfMinFrames(int kfMinFrames) {
        set_kfMinFrames_0(nativeObj, kfMinFrames);
    }


    //
    // C++: int OdometryParams::kfMaxFrames
    //

    public int get_kfMaxFrames() {
        return get_kfMaxFrames_0(nativeObj);
    }


    //
    // C++: void OdometryParams::kfMaxFrames
    //

    public void set_kfMaxFrames(int kfMaxFrames) {
        set_kfMaxFrames_0(nativeObj, kfMaxFrames);
    }


    //
    // C++: double OdometryParams::kfInlierRatio
    //

    public double get_kfInlierRatio() {
        return get_kfInlierRatio_0(nativeObj);
    }


    //
    // C++: void OdometryParams::kfInlierRatio
    //

    public void set_kfInlierRatio(double kfInlierRatio) {
        set_kfInlierRatio_0(nativeObj, kfInlierRatio);
    }


    //
    // C++: int OdometryParams::kfMinInliers
    //

    public int get_kfMinInliers() {
        return get_kfMinInliers_0(nativeObj);
    }


    //
    // C++: void OdometryParams::kfMinInliers
    //

    public void set_kfMinInliers(int kfMinInliers) {
        set_kfMinInliers_0(nativeObj, kfMinInliers);
    }


    //
    // C++: double OdometryParams::kfRotThreshDeg
    //

    public double get_kfRotThreshDeg() {
        return get_kfRotThreshDeg_0(nativeObj);
    }


    //
    // C++: void OdometryParams::kfRotThreshDeg
    //

    public void set_kfRotThreshDeg(double kfRotThreshDeg) {
        set_kfRotThreshDeg_0(nativeObj, kfRotThreshDeg);
    }


    //
    // C++: double OdometryParams::kfTransThresh
    //

    public double get_kfTransThresh() {
        return get_kfTransThresh_0(nativeObj);
    }


    //
    // C++: void OdometryParams::kfTransThresh
    //

    public void set_kfTransThresh(double kfTransThresh) {
        set_kfTransThresh_0(nativeObj, kfTransThresh);
    }


    //
    // C++: int OdometryParams::localMapTopK
    //

    public int get_localMapTopK() {
        return get_localMapTopK_0(nativeObj);
    }


    //
    // C++: void OdometryParams::localMapTopK
    //

    public void set_localMapTopK(int localMapTopK) {
        set_localMapTopK_0(nativeObj, localMapTopK);
    }


    //
    // C++: int OdometryParams::localMapNeighborK
    //

    public int get_localMapNeighborK() {
        return get_localMapNeighborK_0(nativeObj);
    }


    //
    // C++: void OdometryParams::localMapNeighborK
    //

    public void set_localMapNeighborK(int localMapNeighborK) {
        set_localMapNeighborK_0(nativeObj, localMapNeighborK);
    }


    //
    // C++: double OdometryParams::localMapRadius
    //

    public double get_localMapRadius() {
        return get_localMapRadius_0(nativeObj);
    }


    //
    // C++: void OdometryParams::localMapRadius
    //

    public void set_localMapRadius(double localMapRadius) {
        set_localMapRadius_0(nativeObj, localMapRadius);
    }


    @Override
    protected void finalize() throws Throwable {
        delete(nativeObj);
    }



    // C++:   cv::slam::OdometryParams::OdometryParams()
    private static native long OdometryParams_0();

    // C++: int OdometryParams::minInitInliers
    private static native int get_minInitInliers_0(long nativeObj);

    // C++: void OdometryParams::minInitInliers
    private static native void set_minInitInliers_0(long nativeObj, int minInitInliers);

    // C++: double OdometryParams::minInitParallaxDeg
    private static native double get_minInitParallaxDeg_0(long nativeObj);

    // C++: void OdometryParams::minInitParallaxDeg
    private static native void set_minInitParallaxDeg_0(long nativeObj, double minInitParallaxDeg);

    // C++: int OdometryParams::minInitPoints
    private static native int get_minInitPoints_0(long nativeObj);

    // C++: void OdometryParams::minInitPoints
    private static native void set_minInitPoints_0(long nativeObj, int minInitPoints);

    // C++: double OdometryParams::hfRatioThresh
    private static native double get_hfRatioThresh_0(long nativeObj);

    // C++: void OdometryParams::hfRatioThresh
    private static native void set_hfRatioThresh_0(long nativeObj, double hfRatioThresh);

    // C++: double OdometryParams::minGrowthParallaxDeg
    private static native double get_minGrowthParallaxDeg_0(long nativeObj);

    // C++: void OdometryParams::minGrowthParallaxDeg
    private static native void set_minGrowthParallaxDeg_0(long nativeObj, double minGrowthParallaxDeg);

    // C++: double OdometryParams::essentialRansacThresh
    private static native double get_essentialRansacThresh_0(long nativeObj);

    // C++: void OdometryParams::essentialRansacThresh
    private static native void set_essentialRansacThresh_0(long nativeObj, double essentialRansacThresh);

    // C++: double OdometryParams::essentialRansacConfidence
    private static native double get_essentialRansacConfidence_0(long nativeObj);

    // C++: void OdometryParams::essentialRansacConfidence
    private static native void set_essentialRansacConfidence_0(long nativeObj, double essentialRansacConfidence);

    // C++: double OdometryParams::pnpReprojThresh
    private static native double get_pnpReprojThresh_0(long nativeObj);

    // C++: void OdometryParams::pnpReprojThresh
    private static native void set_pnpReprojThresh_0(long nativeObj, double pnpReprojThresh);

    // C++: int OdometryParams::pnpMinInliers
    private static native int get_pnpMinInliers_0(long nativeObj);

    // C++: void OdometryParams::pnpMinInliers
    private static native void set_pnpMinInliers_0(long nativeObj, int pnpMinInliers);

    // C++: int OdometryParams::pnpRansacIters
    private static native int get_pnpRansacIters_0(long nativeObj);

    // C++: void OdometryParams::pnpRansacIters
    private static native void set_pnpRansacIters_0(long nativeObj, int pnpRansacIters);

    // C++: double OdometryParams::pnpConfidence
    private static native double get_pnpConfidence_0(long nativeObj);

    // C++: void OdometryParams::pnpConfidence
    private static native void set_pnpConfidence_0(long nativeObj, double pnpConfidence);

    // C++: double OdometryParams::motionModelRadius
    private static native double get_motionModelRadius_0(long nativeObj);

    // C++: void OdometryParams::motionModelRadius
    private static native void set_motionModelRadius_0(long nativeObj, double motionModelRadius);

    // C++: double OdometryParams::motionModelRadiusWide
    private static native double get_motionModelRadiusWide_0(long nativeObj);

    // C++: void OdometryParams::motionModelRadiusWide
    private static native void set_motionModelRadiusWide_0(long nativeObj, double motionModelRadiusWide);

    // C++: int OdometryParams::motionModelMinMatches
    private static native int get_motionModelMinMatches_0(long nativeObj);

    // C++: void OdometryParams::motionModelMinMatches
    private static native void set_motionModelMinMatches_0(long nativeObj, int motionModelMinMatches);

    // C++: double OdometryParams::descProjThresh
    private static native double get_descProjThresh_0(long nativeObj);

    // C++: void OdometryParams::descProjThresh
    private static native void set_descProjThresh_0(long nativeObj, double descProjThresh);

    // C++: int OdometryParams::opticalFlowMinInliers
    private static native int get_opticalFlowMinInliers_0(long nativeObj);

    // C++: void OdometryParams::opticalFlowMinInliers
    private static native void set_opticalFlowMinInliers_0(long nativeObj, int opticalFlowMinInliers);

    // C++: int OdometryParams::kfMinFrames
    private static native int get_kfMinFrames_0(long nativeObj);

    // C++: void OdometryParams::kfMinFrames
    private static native void set_kfMinFrames_0(long nativeObj, int kfMinFrames);

    // C++: int OdometryParams::kfMaxFrames
    private static native int get_kfMaxFrames_0(long nativeObj);

    // C++: void OdometryParams::kfMaxFrames
    private static native void set_kfMaxFrames_0(long nativeObj, int kfMaxFrames);

    // C++: double OdometryParams::kfInlierRatio
    private static native double get_kfInlierRatio_0(long nativeObj);

    // C++: void OdometryParams::kfInlierRatio
    private static native void set_kfInlierRatio_0(long nativeObj, double kfInlierRatio);

    // C++: int OdometryParams::kfMinInliers
    private static native int get_kfMinInliers_0(long nativeObj);

    // C++: void OdometryParams::kfMinInliers
    private static native void set_kfMinInliers_0(long nativeObj, int kfMinInliers);

    // C++: double OdometryParams::kfRotThreshDeg
    private static native double get_kfRotThreshDeg_0(long nativeObj);

    // C++: void OdometryParams::kfRotThreshDeg
    private static native void set_kfRotThreshDeg_0(long nativeObj, double kfRotThreshDeg);

    // C++: double OdometryParams::kfTransThresh
    private static native double get_kfTransThresh_0(long nativeObj);

    // C++: void OdometryParams::kfTransThresh
    private static native void set_kfTransThresh_0(long nativeObj, double kfTransThresh);

    // C++: int OdometryParams::localMapTopK
    private static native int get_localMapTopK_0(long nativeObj);

    // C++: void OdometryParams::localMapTopK
    private static native void set_localMapTopK_0(long nativeObj, int localMapTopK);

    // C++: int OdometryParams::localMapNeighborK
    private static native int get_localMapNeighborK_0(long nativeObj);

    // C++: void OdometryParams::localMapNeighborK
    private static native void set_localMapNeighborK_0(long nativeObj, int localMapNeighborK);

    // C++: double OdometryParams::localMapRadius
    private static native double get_localMapRadius_0(long nativeObj);

    // C++: void OdometryParams::localMapRadius
    private static native void set_localMapRadius_0(long nativeObj, double localMapRadius);

    // native support for java finalize() or cleaner
    private static native void delete(long nativeObj);

}
