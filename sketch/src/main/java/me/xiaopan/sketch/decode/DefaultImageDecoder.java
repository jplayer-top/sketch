/*
 * Copyright (C) 2013 Peng fei Pan <sky@xiaopan.me>
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.xiaopan.sketch.decode;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory.Options;
import android.os.Build;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import me.xiaopan.sketch.SLog;
import me.xiaopan.sketch.SLogType;
import me.xiaopan.sketch.feature.ImageOrientationCorrector;
import me.xiaopan.sketch.request.LoadRequest;

/**
 * 图片解码器
 */
public class DefaultImageDecoder implements ImageDecoder {
    protected String logName = "DefaultImageDecoder";

    private DecodeTimeAnalyze timeAnalyze = new DecodeTimeAnalyze();
    private List<DecodeHelper> decodeHelperList;
    private List<ResultProcessor> resultProcessorList;

    public DefaultImageDecoder() {
        decodeHelperList = new LinkedList<>();
        resultProcessorList = new LinkedList<>();

        decodeHelperList.add(new ProcessedCacheDecodeHelper());
        decodeHelperList.add(new GifDecodeHelper());
        decodeHelperList.add(new ThumbnailModeDecodeHelper());
        decodeHelperList.add(new NormalDecodeHelper());

        resultProcessorList.add(new CorrectOrientationResultProcessor());
        resultProcessorList.add(new ProcessImageResultProcessor());
        resultProcessorList.add(new ProcessedCacheResultProcessor());
    }

    @Override
    public DecodeResult decode(LoadRequest request) throws DecodeException {
        long startTime = 0;
        if (SLogType.TIME.isEnabled()) {
            startTime = timeAnalyze.decodeStart();
        }

        DecodeResult result = null;
        try {
            result = doDecode(request);
        } catch (DecodeException e) {
            throw e;
        } catch (Throwable e) {
            e.printStackTrace();
        }

        if (SLogType.TIME.isEnabled()) {
            timeAnalyze.decodeEnd(startTime, logName, request.getKey());
        }

        if (result != null) {
            try {
                doProcess(request, result);
            } catch (DecodeException e) {
                result.recycle(request.getConfiguration().getBitmapPool());
                throw e;
            } catch (Throwable e) {
                e.printStackTrace();
                result.recycle(request.getConfiguration().getBitmapPool());
                result = null;
            }
        }

        return result;
    }

    private DecodeResult doDecode(LoadRequest request) throws DecodeException {
        // Make date source
        DataSource dataSource = DataSourceFactory.makeDataSourceByRequest(request, false, logName);

        // Decode bounds and mime info
        Options boundOptions = new Options();
        boundOptions.inJustDecodeBounds = true;
        try {
            ImageDecodeUtils.decodeBitmap(dataSource, boundOptions);
        } catch (IOException e) {
            e.printStackTrace();
            SLog.e(SLogType.REQUEST, logName, "decode bounds failed %s", request.getKey());
            ImageDecodeUtils.decodeError(request, dataSource, logName);
            return null;
        }

        // Exclude images with a width of less than or equal to 1
        if (boundOptions.outWidth <= 1 || boundOptions.outHeight <= 1) {
            SLog.e(SLogType.REQUEST, logName, "image width or height less than or equal to 1px. imageSize: %dx%d. %s",
                    boundOptions.outWidth, boundOptions.outHeight, request.getKey());
            ImageDecodeUtils.decodeError(request, dataSource, logName);
            return null;
        }

        // Read image orientation
        int imageOrientation = 0;
        if (request.getOptions().isCorrectImageOrientation()) {
            ImageOrientationCorrector imageOrientationCorrector = request.getConfiguration().getImageOrientationCorrector();
            imageOrientation = imageOrientationCorrector.readImageRotateDegrees(boundOptions.outMimeType, dataSource);
        }

        ImageType imageType = ImageType.valueOfMimeType(boundOptions.outMimeType);

        // Set whether priority is given to quality or speed
        Options decodeOptions = new Options();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD_MR1
                && request.getOptions().isInPreferQualityOverSpeed()) {
            decodeOptions.inPreferQualityOverSpeed = true;
        }

        // Setup preferred bitmap config
        Bitmap.Config newConfig = request.getOptions().getBitmapConfig();
        if (newConfig == null && imageType != null) {
            newConfig = imageType.getConfig(request.getOptions().isLowQualityImage());
        }
        if (newConfig != null) {
            decodeOptions.inPreferredConfig = newConfig;
        }

        DecodeResult decodeResult = null;
        for (DecodeHelper decodeHelper : decodeHelperList) {
            if (decodeHelper.match(request, dataSource, imageType, boundOptions)) {
                decodeResult = decodeHelper.decode(request, dataSource, imageType, boundOptions, decodeOptions, imageOrientation);
                break;
            }
        }

        if (decodeResult != null) {
            decodeResult.setImageFrom(dataSource.getImageFrom());
        }

        return decodeResult;
    }

    private void doProcess(LoadRequest request, DecodeResult result) throws DecodeException {
        for (ResultProcessor resultProcessor : resultProcessorList) {
            resultProcessor.process(request, result);
        }
    }

    @Override
    public String getKey() {
        return logName;
    }
}