//
// This file is auto-generated. Please don't modify it!
//
package org.opencv.dnn;

import java.util.ArrayList;
import java.util.List;
import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.dnn.Tokenizer;
import org.opencv.utils.Converters;

// C++: class Tokenizer
/**
 * High-level tokenizer wrapper for DNN usage.
 *
 * Provides a simple API to encode and decode tokens for LLMs.
 * Models are loaded via Tokenizer::load().
 *
 * <code>
 * using namespace cv::dnn;
 * Tokenizer tok = Tokenizer::load("/path/to/model/");
 * std::vector&lt;int&gt; ids = tok.encode("hello world");
 * std::string text = tok.decode(ids);
 * </code>
 */
public class Tokenizer {

    protected final long nativeObj;
    protected Tokenizer(long addr) {
      nativeObj = addr;
      
    }

    public long getNativeObjAddr() { return nativeObj; }

    // internal usage only
    public static Tokenizer __fromPtr__(long addr) { return new Tokenizer(addr); }

    //
    // C++: static Tokenizer cv::dnn::Tokenizer::load(string model_config)
    //

    /**
     * Load a tokenizer from a model directory.
     *
     * Accepts either the model directory or the path to its {@code config.json}. The
     * directory must contain:
     * - {@code config.json}. An optional {@code method} field (one of: BPE, Gemma, SentencePiece,
     * Unigram, WordPiece) selects the family; without it the family is detected from
     * {@code tokenizer.json}, so a stock Hugging Face config works unmodified.
     * - {@code tokenizer.json} produced by the corresponding model family.
     *
     * @param model_config  Model directory, or the path to its config.json.
     * @return A Tokenizer ready for use. Throws cv::Exception if files are missing or {@code method} is unsupported.
     */
    public static Tokenizer load(String model_config) {
        return new Tokenizer(load_0(model_config));
    }


    //
    // C++:  vector_int cv::dnn::Tokenizer::encode(string text)
    //

    /**
     * Encode UTF-8 text to token ids.
     *
     * Special-token text in {@code text} is recognized and mapped to its own id, and any
     * wrapper the model's post_processor declares (e.g. BERT's {@code [CLS]}/{@code [SEP]}) is
     * applied.
     *
     * @param text  UTF-8 input string.
     * @return Vector of token ids (32-bit ids narrowed to int for convenience).
     */
    public MatOfInt encode(String text) {
        return MatOfInt.fromNativeAddr(encode_0(nativeObj, text));
    }


    //
    // C++:  vector_int cv::dnn::Tokenizer::encode(vector_string textChunks)
    //

    /**
     * Encode several text chunks as one sequence.
     *
     * The chunks are laid out the way the model's own post_processor declares a pair of
     * sequences, repeated for as many chunks as are given. For a BERT-family tokenizer
     * that is {@code [CLS] textChunks[0] [SEP] textChunks[1] [SEP] ... textChunks[N-1] [SEP]};
     * for T5 it is {@code textChunks[0] &lt;/s&gt; textChunks[1] &lt;/s&gt; ...}. Whatever the template
     * puts between its two sequences is repeated between every neighbouring pair.
     *
     * A single chunk is encoded exactly like encode(const std::string&amp;). Two or more
     * require a tokenizer whose {@code post_processor} declares a {@code pair} template: BERT and
     * ALBERT, T5 and Gemma do; byte-level BPE models such as GPT-2 do not, and throw.
     *
     * @param textChunks  UTF-8 input chunks; must not be empty.
     * @return Vector of token ids. Throws cv::Exception if {@code textChunks} is empty, or if
     * it holds more than one chunk and the loaded tokenizer has no pair template.
     */
    public MatOfInt encode(List<String> textChunks) {
        return MatOfInt.fromNativeAddr(encode_1(nativeObj, textChunks));
    }


    //
    // C++:  string cv::dnn::Tokenizer::decode(vector_int tokens)
    //

    public String decode(MatOfInt tokens) {
        Mat tokens_mat = tokens;
        return decode_0(nativeObj, tokens_mat.nativeObj);
    }


    @Override
    protected void finalize() throws Throwable {
        delete(nativeObj);
    }



    // C++: static Tokenizer cv::dnn::Tokenizer::load(string model_config)
    private static native long load_0(String model_config);

    // C++:  vector_int cv::dnn::Tokenizer::encode(string text)
    private static native long encode_0(long nativeObj, String text);

    // C++:  vector_int cv::dnn::Tokenizer::encode(vector_string textChunks)
    private static native long encode_1(long nativeObj, List<String> textChunks);

    // C++:  string cv::dnn::Tokenizer::decode(vector_int tokens)
    private static native String decode_0(long nativeObj, long tokens_mat_nativeObj);

    // native support for java finalize() or cleaner
    private static native void delete(long nativeObj);

}
