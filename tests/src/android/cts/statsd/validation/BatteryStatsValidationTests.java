/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.cts.statsd.validation;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import android.cts.statsd.atom.DeviceAtomTestCase;
import android.os.BatteryStatsProto;
import android.os.UidProto;

import com.android.internal.os.StatsdConfigProto.StatsdConfig;
import com.android.os.AtomsProto.Atom;
import com.android.os.AtomsProto.DeviceCalculatedPowerBlameUid;
import com.android.os.AtomsProto.DeviceCalculatedPowerUse;
import com.android.os.StatsLog.CountMetricData;
import com.android.tradefed.log.LogUtil;
import com.android.tradefed.log.LogUtil.CLog;

import java.util.List;

/**
 * Side-by-side comparison between statsd and batterystats.
 */
public class BatteryStatsValidationTests extends DeviceAtomTestCase {

    private static final String TAG = "Statsd.BatteryStatsValidationTests";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        resetBatteryStatus();
        unplugDevice();
    }

    @Override
    protected void tearDown() throws Exception {
        plugInUsb();
    }

    public void testConnectivityStateChange() throws Exception {
        if (!hasFeature(FEATURE_WIFI, true)) return;
        if (!hasFeature(FEATURE_WATCH, false)) return;
        final String fileName = "BATTERYSTATS_CONNECTIVITY_STATE_CHANGE_COUNT.pbtxt";
        StatsdConfig config = new ValidationTestUtil().getConfig(fileName);
        LogUtil.CLog.d("Updating the following config:\n" + config.toString());
        uploadConfig(config);

        Thread.sleep(WAIT_TIME_SHORT);

        turnOnAirplaneMode();
        turnOffAirplaneMode();
        // wait for long enough for device to restore connection
        Thread.sleep(10_000);

        BatteryStatsProto batterystatsProto = getBatteryStatsProto();
        List<CountMetricData> countMetricData = getCountMetricDataList();
        assertEquals(1, countMetricData.size());
        assertEquals(1, countMetricData.get(0).getBucketInfoCount());
        assertTrue(countMetricData.get(0).getBucketInfo(0).getCount() > 0);
        assertEquals(batterystatsProto.getSystem().getMisc().getNumConnectivityChanges(),
                countMetricData.get(0).getBucketInfo(0).getCount());
    }

    public void testPowerUse() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_LEANBACK_ONLY, false)) return;
        resetBatteryStats();
        unplugDevice();

        final double ALLOWED_FRACTIONAL_DIFFERENCE = 0.8; // ratio that statsd and bs can differ

        StatsdConfig.Builder config = getPulledConfig();
        addGaugeAtomWithDimensions(config, Atom.DEVICE_CALCULATED_POWER_USE_FIELD_NUMBER, null);
        uploadConfig(config);
        unplugDevice();

        Thread.sleep(WAIT_TIME_LONG);
        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", "testSimpleCpu");
        Thread.sleep(WAIT_TIME_LONG);

        setAppBreadcrumbPredicate();
        BatteryStatsProto batterystatsProto = getBatteryStatsProto();
        Thread.sleep(WAIT_TIME_LONG);
        List<Atom> atomList = getGaugeMetricDataList();

        // Extract statsd data
        Atom atom = atomList.get(0);
        float statsdPower = atom.getDeviceCalculatedPowerUse().getComputedPowerMilliAmpHours();
        assertTrue("Statsd: Non-positive power value.", statsdPower > 0);

        // Extract BatteryStats data
        double bsPower = batterystatsProto.getSystem().getPowerUseSummary().getComputedPowerMah();
        assertTrue("BatteryStats: Non-positive power value.", bsPower > 0);

        assertTrue(String.format("Statsd (%f) < Batterystats (%f)", statsdPower, bsPower),
                statsdPower > ALLOWED_FRACTIONAL_DIFFERENCE * bsPower);
        assertTrue(String.format("Batterystats (%f) < Statsd (%f)", bsPower, statsdPower),
                bsPower > ALLOWED_FRACTIONAL_DIFFERENCE * statsdPower);
    }

    public void testPowerBlameUid() throws Exception {
        if (statsdDisabled()) {
            return;
        }
        if (!hasFeature(FEATURE_LEANBACK_ONLY, false)) return;
        resetBatteryStats();
        unplugDevice();

        final double ALLOWED_FRACTIONAL_DIFFERENCE = 0.8; // ratio that statsd and bs can differ

        StatsdConfig.Builder config = getPulledConfig();
        addGaugeAtomWithDimensions(config, Atom.DEVICE_CALCULATED_POWER_BLAME_UID_FIELD_NUMBER,
                null);
        uploadConfig(config);
        unplugDevice();

        Thread.sleep(WAIT_TIME_LONG);
        runDeviceTests(DEVICE_SIDE_TEST_PACKAGE, ".AtomTests", "testSimpleCpu");
        Thread.sleep(WAIT_TIME_LONG);

        setAppBreadcrumbPredicate();
        BatteryStatsProto batterystatsProto = getBatteryStatsProto();
        Thread.sleep(WAIT_TIME_LONG);
        List<Atom> atomList = getGaugeMetricDataList();

        // Extract statsd data
        boolean uidFound = false;
        int uid = getUid();
        float statsdUidPower = 0;
        for (Atom atom : atomList) {
            DeviceCalculatedPowerBlameUid item = atom.getDeviceCalculatedPowerBlameUid();
            if (item.getUid() == uid) {
                assertFalse("Found multiple power values for uid " + uid, uidFound);
                uidFound = true;
                statsdUidPower = item.getPowerMilliAmpHours();
            }
        }
        assertTrue("Statsd: No power value for uid " + uid, uidFound);
        assertTrue("Statsd: Non-positive power value for uid " + uid, statsdUidPower > 0);

        // Extract batterystats data
        double bsUidPower = -1;
        boolean hadUid = false;
        for (UidProto uidProto : batterystatsProto.getUidsList()) {
            if (uidProto.getUid() == uid) {
                hadUid = true;
                bsUidPower = uidProto.getPowerUseItem().getComputedPowerMah();
            }
        }
        assertTrue("Batterystats: No power value for uid " + uid, hadUid);
        assertTrue("BatteryStats: Non-positive power value for uid " + uid, bsUidPower > 0);

        assertTrue(String.format("Statsd (%f) < Batterystats (%f).", statsdUidPower, bsUidPower),
                statsdUidPower > ALLOWED_FRACTIONAL_DIFFERENCE * bsUidPower);
        assertTrue(String.format("Batterystats (%f) < Statsd (%f).", bsUidPower, statsdUidPower),
                bsUidPower > ALLOWED_FRACTIONAL_DIFFERENCE * statsdUidPower);
    }
}
