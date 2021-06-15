/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at
         http://www.apache.org/licenses/LICENSE-2.0
       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
 */


package org.apache.cordova.geolocation;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.Manifest;

import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationAvailability;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.LocationSettingsStatusCodes;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.apache.cordova.LOG;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Geolocation extends CordovaPlugin implements OnCompleteListener<Location> {
    public final static String TAG = "GeolocationPlugin";
    public final static int PERMISSION_DENIED = 1;
    public final static int POSITION_UNAVAILABLE = 2;
    public final static int REQUEST_LOCATION_ACCURACY_CODE = 235524;
    public final static String [] permissions = { Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION };

    private LocationManager locationManager;
    private FusedLocationProviderClient locationsClient;
    private SettingsClient settingsClient;

    private Map<String, SimpleImmutableEntry<LocationRequest, LocationCallback>> watchers = new HashMap<String, SimpleImmutableEntry<LocationRequest, LocationCallback>>();
    private List<CallbackContext> locationCallbacks = new ArrayList<CallbackContext>();

    @Override
    protected void pluginInitialize() {
        if (hasLocationPermission()) {
            initLocationClient();
        } else {
            PermissionHelper.requestPermissions(this, 0, permissions);
        }
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if ("getLocation".equals(action)) {
            getLocation(args.getBoolean(0), args.getInt(1), callbackContext);
        } else if ("addWatch".equals(action)) {
            addWatch(args.getString(0), args.getBoolean(1), callbackContext);
        } else if ("clearWatch".equals(action)) {
            clearWatch(args.getString(0), callbackContext);
        } else {
            return false;
        }

        return true;
    }

    private void initLocationClient() {
        this.locationManager = (LocationManager) cordova.getActivity().getSystemService(Context.LOCATION_SERVICE);
        this.locationsClient = LocationServices.getFusedLocationProviderClient(cordova.getActivity());
        this.settingsClient = LocationServices.getSettingsClient(cordova.getActivity());

        LocationSettingsRequest.Builder settingsBuilder = new LocationSettingsRequest.Builder();
        settingsBuilder.addLocationRequest(LocationRequest.create().setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY));
        settingsBuilder.addLocationRequest(LocationRequest.create().setPriority(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY));
        settingsBuilder.setAlwaysShow(true);

        this.settingsClient
            .checkLocationSettings(settingsBuilder.build())
            .addOnCompleteListener(cordova.getActivity(), new OnCompleteListener<LocationSettingsResponse>() {
                @Override
                public void onComplete(Task<LocationSettingsResponse> task) {
                    try {
                        LocationSettingsResponse response = task.getResult(ApiException.class);
                        // All location settings are satisfied.
                        startPendingListeners();
                    } catch (ApiException exception) {
                        if (exception.getStatusCode() != LocationSettingsStatusCodes.RESOLUTION_REQUIRED) {
                            startPendingListeners();
                        } else {
                            // Location settings could be fixed by showing a dialog.
                            try {
                                // Cast to a resolvable exception.
                                ResolvableApiException resolvable = (ResolvableApiException) exception;
                                // Show the dialog by calling startResolutionForResult(),
                                // and check the result in onActivityResult().
                                cordova.setActivityResultCallback(Geolocation.this);
                                resolvable.startResolutionForResult(cordova.getActivity(), REQUEST_LOCATION_ACCURACY_CODE);
                            } catch (Exception e) {
                                startPendingListeners();
                            }
                        }
                    }
                }
            });
    }

    private void startPendingListeners() {
        for (SimpleImmutableEntry<LocationRequest, LocationCallback> entry : this.watchers.values()) {
            this.locationsClient.requestLocationUpdates(entry.getKey(), entry.getValue(), null);
        }

        if (this.locationCallbacks.size() > 0) {
            this.locationsClient.getLastLocation()
                .addOnCompleteListener(cordova.getActivity(), this);
        }
    }

    private void getLocation(boolean enableHighAccuracy, int maxAge, CallbackContext callbackContext) {
        if (enableHighAccuracy && isGPSdisabled()) {
            callbackContext.error(createErrorResult(POSITION_UNAVAILABLE));
        } else {
            this.locationCallbacks.add(callbackContext);

            if (hasLocationPermission()) {
                this.locationsClient.getLastLocation()
                    .addOnCompleteListener(cordova.getActivity(), this);
            }
        }
    }

    private void addWatch(String id, boolean enableHighAccuracy, CallbackContext callbackContext) {
        LocationRequest request = LocationRequest.create();

        if (enableHighAccuracy) {
            if (hasLocationPermission() && isGPSdisabled()) {
                callbackContext.error(createErrorResult(POSITION_UNAVAILABLE));
                return;
            }

            request.setInterval(5000);
            request.setSmallestDisplacement(5);
            request.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        } else {
            request.setInterval(5000);
            request.setSmallestDisplacement(10);
            request.setPriority(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY);
        }

        LocationCallback locationCallback = new LocationCallback() {
            @Override
            public void onLocationAvailability(LocationAvailability locationAvailability) {
                LOG.d(TAG, "onLocationAvailability");

                if (!locationAvailability.isLocationAvailable()) {
                    PluginResult pluginResult = new PluginResult(PluginResult.Status.ERROR, createErrorResult(POSITION_UNAVAILABLE));
                    pluginResult.setKeepCallback(true);
                    callbackContext.sendPluginResult(pluginResult);
                }
            }

            @Override
            public void onLocationResult(LocationResult result) {
                LOG.d(TAG, "onLocationResult");

                Location location = result.getLastLocation();
                if (location != null) {
                    PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, createResult(location));
                    pluginResult.setKeepCallback(true);
                    callbackContext.sendPluginResult(pluginResult);
                }
            }
        };

        this.watchers.put(id, new SimpleImmutableEntry(request, locationCallback));

        if (hasLocationPermission()) {
            this.locationsClient.requestLocationUpdates(request, locationCallback, null);
        }
    }

    private void clearWatch(String id, CallbackContext callbackContext) {
        SimpleImmutableEntry<LocationRequest, LocationCallback> entry = this.watchers.get(id);
        if (entry != null) {
            this.watchers.remove(id);
            if (hasLocationPermission()) {
                this.locationsClient.removeLocationUpdates(entry.getValue());
            }
        }

        callbackContext.success();
    }

    private boolean isGPSdisabled() {
        return !this.locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    @Override
    public void onPause(boolean multitasking) {
        if (this.locationsClient != null) {
            for (SimpleImmutableEntry<LocationRequest, LocationCallback> entry : this.watchers.values()) {
                this.locationsClient.removeLocationUpdates(entry.getValue());
            }
        }
    }

    @Override
    public void onResume(boolean multitasking) {
        if (this.locationsClient != null) {
            for (SimpleImmutableEntry<LocationRequest, LocationCallback> entry : this.watchers.values()) {
                this.locationsClient.requestLocationUpdates(entry.getKey(), entry.getValue(), null);
            }
        }
    }

    @Override
    public void onComplete(Task<Location> task) {
        PluginResult pluginResult;

        if (task.isSuccessful()) {
            Location location = task.getResult();
            if (location == null) {
                LOG.d(TAG, "Got null location");

                pluginResult = new PluginResult(PluginResult.Status.ERROR,
                    createErrorResult(POSITION_UNAVAILABLE));
            } else {
                LOG.d(TAG, "Got last location");

                pluginResult = new PluginResult(PluginResult.Status.OK,
                    createResult(location));
            }
        } else {
            LOG.e(TAG, "Fail to get last location");

            pluginResult = new PluginResult(PluginResult.Status.ERROR,
                task.getException().getMessage());
        }

        for (CallbackContext callback : this.locationCallbacks) {
            callback.sendPluginResult(pluginResult);
        }
        this.locationCallbacks.clear();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_LOCATION_ACCURACY_CODE) {
            startPendingListeners();
        }
    }

    public void onRequestPermissionResult(int requestCode, String[] permissions,
                                          int[] grantResults) throws JSONException {
        for (int r : grantResults) {
            if (r == PackageManager.PERMISSION_DENIED) {
                LOG.d(TAG, "Permission Denied!");
                return;
            }
        }

        initLocationClient();
    }

    private static JSONObject createResult(Location loc) {
        JSONObject result = new JSONObject();

        try {
            result.put("timestamp", loc.getTime());
            result.put("velocity", loc.getSpeed());
            result.put("accuracy", loc.getAccuracy());
            result.put("heading", loc.getBearing());
            result.put("altitude", loc.getAltitude());
            result.put("latitude", loc.getLatitude());
            result.put("longitude", loc.getLongitude());

            return result;
        } catch (JSONException e) {
            LOG.e(TAG, "Fail to convert");

            return null;
        }
    }

    private static JSONObject createErrorResult(int code) {
        JSONObject result = new JSONObject();

        try {
            result.put("code", code);

            return result;
        } catch (JSONException e) {
            LOG.e(TAG, "Fail to convert");

            return null;
        }
    }

    public boolean hasLocationPermission() {
        for (String p : permissions) {
            if (!PermissionHelper.hasPermission(this, p)) {
                return false;
            }
        }
        return true;
    }

    /*
     * We override this so that we can access the permissions variable, which no longer exists in
     * the parent class, since we can't initialize it reliably in the constructor!
     */

    public void requestPermissions(int requestCode) {
        PermissionHelper.requestPermissions(this, requestCode, permissions);
    }
}
