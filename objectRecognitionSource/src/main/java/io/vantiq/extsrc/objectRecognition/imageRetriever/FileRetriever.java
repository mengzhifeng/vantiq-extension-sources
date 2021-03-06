
/*
 * Copyright (c) 2018 Vantiq, Inc.
 *
 * All rights reserved.
 * 
 * SPDX: MIT
 */

package io.vantiq.extsrc.objectRecognition.imageRetriever;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import io.vantiq.extsrc.objectRecognition.ObjectRecognitionCore;
import io.vantiq.extsrc.objectRecognition.exception.FatalImageException;
import io.vantiq.extsrc.objectRecognition.exception.ImageAcquisitionException;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;

/**
 * This implementation reads files from the disk, using OpenCV for the videos. {@code fileLocation} must be a valid file
 * at initialization if specified for a video. The initial image file can be replaced while the source is running, but the video cannot. For
 * Queries, new files can be specified using the {@code fileLocation} and {@code fileExtension} options, and defaults
 * to the initial file if {@code fileLocation} is not set. Queried videos can specify which frame of the video to access
 * using the {@code targetFrame} option.
 * <br>
 * Errors are thrown whenever an image or video frame cannot be read. Fatal errors are thrown only when a video finishes
 * being read when the source is not setup for to receive Queries.
 * <br>
 * The options are as follows. Remember to prepend "DS" when using an option in a Query.
 * <ul>
 *     <li>{@code fileLocation}: Optional. Config and  Query. The location of the file to be read.
 *                      For Config where {@code fileExtension} is "mov", the file must exist at initialization. If this
 *                      option is not set at Config and the source is configured to poll, then the source will open but
 *                      the first attempt to retrieve will kill the source. For Queries, defaults to the configured file
 *                      or returns an error if there was none.
 *     <li>{@code fileExtension}: Optional. Config and Query. The type of file it is, "mov" for video files, "img" for
 *                      image files. Defaults to image files.
 *     <li>{@code fps}: Optional. Config only. Requires {@code fileExtension} be "mov". How many frames to retrieve for
 *                      every second in the video. Rounds up the result when calculating the number of frames to move
 *                      each capture. Non-positive numbers revert to default. Default is every frame.
 *     <li>{@code targetFrame}: Optional. Query only. Requires {@code fileExtension} be "mov". The frame in the video
 *                      that you would like to access, with the first being 0. Exceptions will be thrown if this targets
 *                      an invalid frame, i.e. negative or beyond the video's frame count. Mutually exclusive with
 *                      {@code targetTime}. Defaults to 0.
 *     <li>{@code targetTime}: Optional. Query only. Requires {@code fileExtension} be "mov". The second in the video
 *                      that you would like to access, with the first frame being 0. Exceptions will be thrown if this
 *                      targets an invalid frame, i.e. negative or beyond the video's frame count. Non-integer values 
 *                      are allowed. Mutually exclusive with {@code targetFrame}. Defaults to 0.
 * </ul>
 * 
 * No timestamp is captured. The additional data is:
 * <ul>
 *      <li>{@code file}: The path of the file read.
 *      <li>{@code frame}: Which frame of the file this represents. Only included when `fileExtension` is set to "mov".
 * </ul>
 */
public class FileRetriever implements ImageRetrieverInterface {

    String defaultImageLocation;
    VideoCapture capture;
    Boolean isMov = false;
    int frameInterval;
    
    // Constants for source configuration
    private static final String FILE_EXTENSION = "fileExtension";
    private static final String FILE_LOCATION = "fileLocation";
    private static final String FPS = "fps";

    @Override
    public void setupDataRetrieval(Map<String, ?> dataSourceConfig, ObjectRecognitionCore source) throws Exception {
        // Check if the file is a video
        if (dataSourceConfig.get(FILE_EXTENSION) instanceof String) {
            String ext = (String) dataSourceConfig.get(FILE_EXTENSION);
            if (ext.equals("mov") || ext.equals("mp4")) {
                isMov = true;
            }
        }

        // Load OpenCV
        try {
            System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
        } catch (Throwable t) {
            throw new Exception(this.getClass().getCanonicalName() + ".opencvDependency" 
                    + ": Could not load OpenCv for FileRetriever."
                    + "This is most likely due to a missing .dll/.so/.dylib. Please ensure that the environment "
                    + "variable 'OPENCV_LOC' is set to the directory containing '" + Core.NATIVE_LIBRARY_NAME
                    + "' and any other library requested by the attached error", t);
        }
        
        // Save the initial file location
        if (dataSourceConfig.get(FILE_LOCATION) instanceof String) {
            defaultImageLocation = (String) dataSourceConfig.get(FILE_LOCATION);
            // Setup OpenCV to read the video if the file is a video
            if (isMov) {
                // Open the requested file
                capture = new VideoCapture(defaultImageLocation);
                
                // Exit if the video cannot be read
                if (!capture.isOpened()) {
                    capture.release();
                    if (!new File(defaultImageLocation).exists()) {
                        throw new ImageAcquisitionException(this.getClass().getCanonicalName() + ".mainVideoDoesNotExist: "
                                + "The requested video '" + defaultImageLocation + "' does not exist");
                    }
                    throw new IllegalArgumentException(this.getClass().getCanonicalName() + ".invalidMainVideo: " 
                            + "Intended video '" + defaultImageLocation + "' could not be opened. Most likely OpenCV is not "
                            + "compiled with the codecs required to read this video type");
                }
                
                // Obtain the frame rate of the video, defaulting to 24
                double videoFps = capture.get(Videoio.CAP_PROP_FPS);
                if (videoFps == 0) {
                    videoFps = 24;
                }
                
                // Calculate the number of frames to move each capture
                double fps = 0;
                if (dataSourceConfig.get(FPS) instanceof Number) {
                    fps = ((Number) dataSourceConfig.get(FPS)).doubleValue();
                }
                if (fps <= 0) {
                    frameInterval = 1;
                } else {
                    frameInterval = (int) Math.ceil(videoFps / fps);
                }
                
            }
        }
    }

    /**
     * Read the file specified at configuration
     * @throws FatalImageException  If no file was specified at configuration, or if the video has completed
     */
    @Override
    public ImageRetrieverResults getImage() throws ImageAcquisitionException {
        ImageRetrieverResults results = new ImageRetrieverResults();
        Map<String, Object> otherData = new LinkedHashMap<>();
        
        results.setOtherData(otherData);
        otherData.put("file", defaultImageLocation);
        
        if (isMov) {
            Mat matrix = new Mat();
            // Save the current position for later.
            double val = capture.get(Videoio.CAP_PROP_POS_FRAMES);
            
            capture.read(matrix);
            // Exit if nothing could be read
            if (matrix.empty()) {
                capture.release();
                matrix.release();
                throw new FatalImageException(this.getClass().getCanonicalName() + ".defaultVideoReadError: " 
                         + "Default video could not be read. Most likely the video completed reading.");
            }
            
            // Move forward by the number of frames specified in the Configuration
            val += frameInterval;
            capture.set(Videoio.CAP_PROP_POS_FRAMES, val);
            
            // Translate the image to jpeg
            byte[] imageBytes = convertToJpeg(matrix);
            if (imageBytes == null) {
                throw new ImageAcquisitionException(this.getClass().getCanonicalName() + ".videoConversionError: " 
                        + "Could not convert frame #" + val + " from video '" + defaultImageLocation 
                        + "' into a jpeg image");
            }
            
            otherData.put("frame", val);
            results.setImage(imageBytes);
                    
            return results;
        } else if (defaultImageLocation != null){
            // Read the expected image
            otherData.put("file", defaultImageLocation);
            Mat image = Imgcodecs.imread(defaultImageLocation);
            if (image == null || image.empty()) {
                if (image != null) {
                    image.release();
                }
                
                if (!new File(defaultImageLocation).exists()) {
                    throw new ImageAcquisitionException(this.getClass().getCanonicalName() + ".defaultImageDoesNotExist: "
                            + "The default image does not exist");
                }
                
                throw new ImageAcquisitionException(this.getClass().getCanonicalName() + ".defaultImageUnreadable: " 
                        + "Could not read requested file '" + defaultImageLocation + "'. "
                        + "Most likely the image was in an unreadable format");
            }
            
            byte[] jpegImage = convertToJpeg(image);
            if (jpegImage == null) {
                throw new ImageAcquisitionException(this.getClass().getCanonicalName() + ".imageConversionError: " 
                        + "Could not convert file '" + defaultImageLocation + "' into a jpeg image");
            }
            
            results.setImage(jpegImage);
            return results;
        } else {
            throw new FatalImageException(this.getClass().getCanonicalName() + ".noDefaultFile: " 
                    + "No default file found. Most likely none was specified in the configuration.");
        }
    }

    /**
     * Read the specified file, or the file specified at configuration
     */
    @Override
    public ImageRetrieverResults getImage(Map<String, ?> request) throws ImageAcquisitionException {
        ImageRetrieverResults results = new ImageRetrieverResults();
        Map<String, Object> otherData = new LinkedHashMap<>();
        
        results.setOtherData(otherData);
        
        boolean isMov = false; // Make it local so we don't overwrite the class variable
        
        // Check if the file is expected to be a video
        if (request.get("DSfileExtension") instanceof String) {
            String ext = (String) request.get("DSfileExtension");
            if (ext.equals("mov") || ext.equals("mp4")) {
                isMov = true;
            }
        }
        
        // Read in the file specified, or try the default if no file is specified
        if (request.get("DSfileLocation") instanceof String) {
            if (isMov) {
                String imageFile = (String) request.get("DSfileLocation");
                otherData.put("file", imageFile);
                
                VideoCapture newcapture = new VideoCapture(imageFile);
                Mat matrix = new Mat();
                
                // Make sure the video opened
                if (!newcapture.isOpened()) {
                    newcapture.release();
                    matrix.release();
                    
                    if (!new File(imageFile).exists()) {
                        throw new ImageAcquisitionException(this.getClass().getCanonicalName() + ".queryVideoDoesNotExist: "
                                + "The requested video '" + imageFile + "' does not exist");
                    }
                    throw new ImageAcquisitionException(this.getClass().getCanonicalName() + ".invalidVideoQueried: " 
                            + "Requested video '" + imageFile + "' could not be opened");
                }
                
                // Calculate the specified frame
                int targetFrame = 0;
                if (request.get("DStargetFrame") instanceof Number) {
                    targetFrame = ((Number) request.get("DStargetFrame")).intValue();
                } else if (request.get("DStargetTime") instanceof Number) {
                    double fps = newcapture.get(Videoio.CAP_PROP_FPS);
                    if (fps == 0) {
                        fps = 24;
                    }
                    targetFrame = (int) (fps * ((Number)request.get("DStargetTime")).doubleValue());
                }
                
                // Ensure that targetFrame is inside the bounds of the video, and that we know how many frames are
                // in the video
                double frameCount = newcapture.get(Videoio.CAP_PROP_FRAME_COUNT);
                if (frameCount == 0) {
                    newcapture.release();
                    matrix.release();
                    throw new ImageAcquisitionException(this.getClass().getCanonicalName() + ".videoPropertyError: " 
                            + "Video '" + imageFile + "' registers as 0 frames long");
                } else if (targetFrame >= frameCount || targetFrame < 0) {
                    newcapture.release();
                    matrix.release();
                    throw new ImageAcquisitionException(this.getClass().getCanonicalName() + ".invalidTargetFrame: " 
                            + "Requested frame " + targetFrame + " outside valid bounds (0," + frameCount + ") for "
                            + "video '"+ imageFile + "'");
                }
                newcapture.set(Videoio.CAP_PROP_POS_FRAMES, targetFrame);
                
                newcapture.read(matrix);
                newcapture.release();
                // Exit if nothing could be read
                if (matrix.empty()) {
                    newcapture.release();
                    matrix.release();
                    
                    throw new ImageAcquisitionException(this.getClass().getCanonicalName() + ".videoUnreadable: " 
                            + "Video '" + imageFile + "' could not be read");
                }
                
                // Translate the image to jpeg
                byte[] imageBytes = convertToJpeg(matrix);
                if (imageBytes == null) {
                    throw new ImageAcquisitionException(this.getClass().getCanonicalName() + ".queryVideoConversionError: " 
                            + "Could not convert frame #" + targetFrame + " from video '" + imageFile
                            + "' into a jpeg image");
                }
                        
                otherData.put("frame", targetFrame);
                results.setImage(imageBytes);
                        
                return results;
            } else {
                // Read the expected image
                String imageFile = (String) request.get("DSfileLocation");
                otherData.put("file", imageFile);
                Mat image = Imgcodecs.imread(imageFile);
                if (image == null || image.empty()) {
                    if (image != null) {
                        image.release();
                    }
                    
                    if (!new File(imageFile).exists()) {
                        throw new ImageAcquisitionException(this.getClass().getCanonicalName() + ".queryImageDoesNotExist: "
                                + "The requested image '" + imageFile + "' does not exist");
                    }
                    throw new ImageAcquisitionException(this.getClass().getCanonicalName() + ".queryImageUnreadable: " 
                            + "Could not read requested file '" + imageFile + "'. "
                            + "Most likely the image was in an unreadable format");
                }
                
                byte[] jpegImage = convertToJpeg(image);
                if (jpegImage == null) {
                    throw new ImageAcquisitionException(this.getClass().getCanonicalName() + ".queryImageConversionError: " 
                            + "Could not convert file '" + defaultImageLocation + "' into a jpeg image");
                }
                
                results.setImage(jpegImage);
                return results;
            }
        } else {
            // Only try to use default if it is set
            if ((isMov && capture.isOpened()) || defaultImageLocation != null) {
                try {
                    return getImage();
                } catch (FatalImageException e) {
                    // Fatal Image only thrown when a video is complete
                    // Since the source can read other videos as well, we don't want to fatally end
                    throw new ImageAcquisitionException(e.getMessage(), e);
                }
            } else {
                throw new ImageAcquisitionException(this.getClass().getCanonicalName() + ".noDefaultFile: " 
                        + "No default file available. Most likely the default video has been completed");
            }
        }
    }

    @Override
    public void close() {
        if (capture != null) {
            capture.release();
        }
    }

    /**
     * Converts an image into jpeg format and releases the Mat that held the original image
     * @param image The image to convert
     * @return      The bytes of the image in jpeg format, or null if it could not be converted 
     */
    byte[] convertToJpeg(Mat image) {
        MatOfByte matOfByte = new MatOfByte();
        // Translate the image into jpeg, return null if it cannot
        if (!Imgcodecs.imencode(".jpg", image, matOfByte)) {
            matOfByte.release();
            image.release();
            return null;
        }
        byte [] imageByte = matOfByte.toArray();
        matOfByte.release();
        image.release();
        
        return imageByte;
    }
}
