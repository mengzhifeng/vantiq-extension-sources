/*
 * Copyright (c) 2018 Vantiq, Inc.
 *
 * All rights reserved.
 * 
 * SPDX: MIT
 */

package io.vantiq.extsrc.HikVisionSource;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.vantiq.extjsdk.ExtensionServiceMessage;

public class TestHikVisionConfig extends TestHikVisionBase {

    HikVisionHandleConfiguration handler;
    
    NoSendHikVisionCore nCore;
    
    String sourceName;
    String authToken;
    String targetVantiqServer;
    
    Map<String, Object> general;
    
    @Before
    public void setup() {
        sourceName = "src";
        authToken = "token";
        targetVantiqServer = "dev.vantiq.com";
        nCore = new NoSendHikVisionCore(sourceName, authToken, targetVantiqServer);
        handler = new HikVisionHandleConfiguration(nCore);
    }
    
    @After
    public void tearDown() {
        nCore.stop();
    }
    /*
    @Test
    public void testEmptyConfig() {
        Map conf = new LinkedHashMap<>();
        Map vantiqConf = new LinkedHashMap<>();
        sendConfig(conf, vantiqConf);
        assertTrue("Should fail on empty configuration", configIsFailed());
    }
    */
    /*
    @Test
    public void testMissingGeneral() {
        Map conf = minimalConfig();
        Map vantiqConf = createMinimalVantiq();
        conf.remove("general");
        sendConfig(conf, vantiqConf);
        assertTrue("Should fail when missing 'general' configuration", configIsFailed());
    }
    */
    /*
    @Test 
    public void testMissingVantiq() {
        Map conf = minimalConfig();
        Map vantiqConf = new LinkedHashMap<>();
        sendConfig(conf, vantiqConf);
        assertTrue("Should fail when missing 'vantiq' configuration", configIsFailed());
    }
    */
    /*
    @Test 
    public void testMissingPackageRows() {
        Map conf = minimalConfig();
        Map vantiqConf = createMinimalVantiq();
        vantiqConf.remove("packageRows");
        sendConfig(conf, vantiqConf);
        assertTrue("Should fail when missing 'packageRows' configuration", configIsFailed());
    }
    */
    /*
    @Test
    public void testPackageRowsFalse() {
        Map conf = minimalConfig();
        Map vantiqConf = createMinimalVantiq();
        vantiqConf.put("packageRows","false");
        sendConfig(conf, vantiqConf);
        assertTrue("Should fail when 'packageRows' is set to 'false'", configIsFailed());
    }
    */

    @Test
    public void testMinimalConfig() {
        nCore.start(5); // Need a client to avoid NPEs on sends
        
        Map conf = minimalConfig();
        Map vantiqConf = createMinimalVantiq();
        sendConfig(conf, vantiqConf);
        assertFalse("Should not fail with minimal configuration", configIsFailed());
    }
    
    @Test
    public void testPollingConfig() {
        nCore.start(5);
        
        Map conf = minimalConfig();
        conf.put("pollTime", 3000);
        conf.put("pollQuery", "SELECT * FROM Test");
        Map vantiqConf = createMinimalVantiq();
        sendConfig(conf, vantiqConf);
        assertFalse("Should not fail with polling configuration", configIsFailed());
        
        conf.remove("pollQuery");
        sendConfig(conf, vantiqConf);
        assertFalse("Should not fail with missing pollQuery configuration", configIsFailed());
        
        conf.remove("pollTime");
        conf.put("pollQuery", "SELECT * FROM Test");
        sendConfig(conf, vantiqConf);
        assertFalse("Should not fail with missing pollTime configuration", configIsFailed());
    }

    @Test
    public void testAsynchronousProcessing() {
        nCore.start(5);

        // Setting asynchronousProcessing incorrectly
        Map conf = minimalConfig();
        conf.put("asynchronousProcessing", "jibberish");
        Map vantiqConf = createMinimalVantiq();
        sendConfig(conf, vantiqConf);
        assertFalse("Should not fail with invalid asynchronousProcessing value", configIsFailed());

        // Setting asynchronousProcessing to false (same as not including it)
        conf.put("asynchronousProcessing", false);
        sendConfig(conf, vantiqConf);
        assertFalse("Should not fail with asynchronousProcessing set to false", configIsFailed());

        // Setting asynchronousProcessing to true
        conf.put("asynchronousProcessing", true);
        sendConfig(conf, vantiqConf);
        assertFalse("Should not fail with asynchronousProcessing set to true", configIsFailed());

        // Setting maxRunningThreads and maxQueuedTasks incorrectly
        conf.put("maxActiveTasks", "jibberish");
        conf.put("maxQueuedTasks", "moreJibberish");
        sendConfig(conf, vantiqConf);
        assertFalse("Should not fail when maxActiveTasks and maxQueuedTasks are set incorrectly", configIsFailed());

        // Setting maxRunningThreads and maxQueuedTasks correctly
        conf.put("maxActiveTasks", 10);
        conf.put("maxQueuedTasks", 20);
        sendConfig(conf, vantiqConf);
        assertFalse("Should not fail when maxActiveTasks and maxQueuedTasks are set correctly", configIsFailed());
    }
    
// ================================================= Helper functions =================================================
    
    public void sendConfig(Map<String, ?> csvConfig, Map<String, ?> vantiqConfig) {
        ExtensionServiceMessage m = new ExtensionServiceMessage("");
        
        Map<String, Object> obj = new LinkedHashMap<>();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("csvConfig", csvConfig);
        config.put("vantiq", vantiqConfig);
        obj.put("config", config);
        m.object = obj;
        
        handler.handleMessage(m);
    }
    
    public Map<String, Object> minimalConfig() {
        createMinimalGeneral();
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("general", general);
        
        return ret;
    }
    
    public void createMinimalGeneral() {
        general = new LinkedHashMap<>();
        general.put("fileFolderPath", testFileFolderPath);
        general.put("filePrefix", testFilePrefix);
        general.put("fileExtension", testFileExtension);


    }
    
    public Map<String, String> createMinimalVantiq() {
        Map<String, String> vantiq = new LinkedHashMap<>();
        vantiq.put("packageRows", "true");
        return vantiq;
    }
    
    public boolean configIsFailed() {
        return handler.isComplete() && nCore.isClosed();
    }
}