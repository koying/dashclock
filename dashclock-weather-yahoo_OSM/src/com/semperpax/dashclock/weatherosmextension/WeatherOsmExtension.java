/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.semperpax.dashclock.weatherosmextension;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import com.google.android.apps.dashclock.api.DashClockExtension;
import com.google.android.apps.dashclock.api.ExtensionData;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;

/**
 * A local weather and forecast extension.
 */
public class WeatherOsmExtension extends DashClockExtension {
    private static final String TAG = "WeatherOsmExtension";

    public static final String PREF_WEATHER_UNITS = "pref_weather_units";
    public static final String PREF_WEATHER_SHORTCUT = "pref_weather_shortcut";
    public static final Intent DEFAULT_WEATHER_INTENT = new Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=weather"));

    private static final long STALE_LOCATION_NANOS = 10l * 60000000000l; // 10 minutes

    private static XmlPullParserFactory sXmlPullParserFactory;

    private static final Criteria sLocationCriteria;

    private static String sWeatherUnits = "f";
    private static Intent sWeatherIntent;

    private boolean mOneTimeLocationListenerActive = false;

    static {
        sLocationCriteria = new Criteria();
        sLocationCriteria.setPowerRequirement(Criteria.POWER_LOW);
        sLocationCriteria.setAccuracy(Criteria.ACCURACY_COARSE);
        sLocationCriteria.setCostAllowed(false);
    }

    static {
        try {
            sXmlPullParserFactory = XmlPullParserFactory.newInstance();
            sXmlPullParserFactory.setNamespaceAware(true);
        } catch (XmlPullParserException e) {
            Log.e(TAG, "Could not instantiate XmlPullParserFactory", e);
        }
    }

    @Override
    protected void onUpdateData(int reason) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        sWeatherUnits = sp.getString(PREF_WEATHER_UNITS, sWeatherUnits);
//        sWeatherIntent = AppChooserPreference.getIntentValue(
//                sp.getString(PREF_WEATHER_SHORTCUT, null), DEFAULT_WEATHER_INTENT);

        NetworkInfo ni = ((ConnectivityManager) getSystemService(
                Context.CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
        if (ni == null || !ni.isConnected()) {
            Log.e(TAG, "No connectivity.");
            return;
        }

        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        String provider = lm.getBestProvider(sLocationCriteria, true);
        if (TextUtils.isEmpty(provider)) {
            Log.e(TAG, "No available location providers matching criteria.");
            return;
        }

        final Location lastLocation = lm.getLastKnownLocation(provider);
        if (lastLocation == null ||
                (SystemClock.elapsedRealtimeNanos() - lastLocation.getElapsedRealtimeNanos())
                        >= STALE_LOCATION_NANOS) {
            Log.w(TAG, "Stale or missing last-known location; requesting single coarse location "
                    + "update.");
            disableOneTimeLocationListener();
            mOneTimeLocationListenerActive = true;
            lm.requestSingleUpdate(provider, mOneTimeLocationListener, null);
        } else {
            getWeatherAndTryPublishUpdate(lastLocation);
        }
    }

    private void disableOneTimeLocationListener() {
        if (mOneTimeLocationListenerActive) {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            lm.removeUpdates(mOneTimeLocationListener);
            mOneTimeLocationListenerActive = false;
        }
    }

    private LocationListener mOneTimeLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            getWeatherAndTryPublishUpdate(location);
            disableOneTimeLocationListener();
        }

        @Override
        public void onStatusChanged(String s, int i, Bundle bundle) {
        }

        @Override
        public void onProviderEnabled(String s) {
        }

        @Override
        public void onProviderDisabled(String s) {
        }
    };

    @Override
    public void onDestroy() {
        super.onDestroy();
        disableOneTimeLocationListener();
    }

    private void getWeatherAndTryPublishUpdate(Location location) {
        try {
            WeatherData weatherData = getWeatherForLocation(location);
            publishUpdate(renderExtensionData(weatherData));
        } catch (InvalidLocationException e) {
            Log.w(TAG, "Could not determine a valid location for weather.", e);
        } catch (IOException e) {
            Log.w(TAG, "Generic read error while retrieving weather information.", e);
        }
    }

    private ExtensionData renderExtensionData(WeatherData weatherData) {
        ExtensionData extensionData = new ExtensionData();
        if (weatherData == null) {
            extensionData
                    .icon(R.drawable.ic_weather_clear)
                    .status(getString(R.string.status_none))
                    .expandedBody(getString(R.string.no_weather_data));
        } else {
            String temperature = weatherData.hasValidTemperature()
                    ? getString(R.string.temperature_template, weatherData.temperature)
                    : getString(R.string.status_none);
            StringBuilder expandedBody = new StringBuilder();

            int conditionIconId = WeatherData.getConditionIconId(weatherData.conditionCode);
            if (WeatherData.getConditionIconId(weatherData.todayForecastConditionCode)
                    == R.drawable.ic_weather_raining) {
                // Show rain if it will rain today.
                conditionIconId = R.drawable.ic_weather_raining;
                expandedBody.append(
                        getString(R.string.later_forecast_template, weatherData.forecastText));
            }

            if (expandedBody.length() > 0) {
                expandedBody.append("\n");
            }
            expandedBody.append(weatherData.location);

            extensionData
                    .status(temperature)
                    .expandedTitle(getString(R.string.weather_expanded_title_template,
                            temperature + sWeatherUnits.toUpperCase(Locale.US),
                            weatherData.conditionText))
                    .icon(conditionIconId)
                    .expandedBody(expandedBody.toString());
        }

        return extensionData
                .visible(true)
                .clickIntent(sWeatherIntent);
    }

    private static WeatherData getWeatherForLocation(Location location)
            throws InvalidLocationException, IOException {

        Log.d(TAG, "Using location: " + location.getLatitude()
                    + "," + location.getLongitude());

        // Honolulu = 2423945
        // Paris = 615702
        // London = 44418
        // New York = 2459115
        // San Francisco = 2487956
        LocationInfo locationInfo = getLocationInfo(location);

        // Loop through the woeids (they're in descending precision order) until weather data
        // is found.
        for (String woeid : locationInfo.woeids) {
            Log.d(TAG, "Trying WOEID: " + woeid);
            WeatherData data = getWeatherDataForWoeid(woeid, locationInfo.town);
            if (data.conditionCode != WeatherData.INVALID_CONDITION
                    && data.temperature != WeatherData.INVALID_TEMPERATURE) {
                return data;
            }
        }

        // No weather could be found :(
        return null;
    }

    private static WeatherData getWeatherDataForWoeid(String woeid, String town)
            throws IOException {
        HttpURLConnection connection = openUrlConnection(buildWeatherQueryUrl(woeid));

        try {
            XmlPullParser xpp = sXmlPullParserFactory.newPullParser();
            xpp.setInput(new InputStreamReader(connection.getInputStream()));

            WeatherData data = new WeatherData();
            boolean hasTodayForecast = false;
            int eventType = xpp.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG
                        && "condition".equals(xpp.getName())) {
                    for (int i = xpp.getAttributeCount() - 1; i >= 0; i--) {
                        if ("temp".equals(xpp.getAttributeName(i))) {
                            data.temperature = Integer.parseInt(xpp.getAttributeValue(i));
                        } else if ("code".equals(xpp.getAttributeName(i))) {
                            data.conditionCode = Integer.parseInt(xpp.getAttributeValue(i));
                        } else if ("text".equals(xpp.getAttributeName(i))) {
                            data.conditionText = xpp.getAttributeValue(i);
                        }
                    }
                } else if (eventType == XmlPullParser.START_TAG
                        && "forecast".equals(xpp.getName())
                        && !hasTodayForecast) {
                    // TODO: verify this is the forecast for today (this currently assumes the
                    // first forecast is today's forecast)
                    hasTodayForecast = true;
                    for (int i = xpp.getAttributeCount() - 1; i >= 0; i--) {
                        if ("code".equals(xpp.getAttributeName(i))) {
                            data.todayForecastConditionCode
                                    = Integer.parseInt(xpp.getAttributeValue(i));
                        } else if ("text".equals(xpp.getAttributeName(i))) {
                            data.forecastText = xpp.getAttributeValue(i);
                        }
                    }
                } else if (eventType == XmlPullParser.START_TAG
                        && "location".equals(xpp.getName())) {
                    String cityOrVillage = "--";
                    String region = null;
                    String country = "--";
                    for (int i = xpp.getAttributeCount() - 1; i >= 0; i--) {
                        if ("city".equals(xpp.getAttributeName(i))) {
                            cityOrVillage = xpp.getAttributeValue(i);
                        } else if ("region".equals(xpp.getAttributeName(i))) {
                            region = xpp.getAttributeValue(i);
                        } else if ("country".equals(xpp.getAttributeName(i))) {
                            country = xpp.getAttributeValue(i);
                        }
                    }

                    if (TextUtils.isEmpty(region)) {
                        // If no region is available, show the country. Otherwise, don't
                        // show country information.
                        region = country;
                    }

                    if (!TextUtils.isEmpty(town) && !town.equals(cityOrVillage)) {
                        // If a town is available and it's not equivalent to the city name,
                        // show it.
                        cityOrVillage = cityOrVillage + ", " + town;
                    }

                    data.location = cityOrVillage + ", " + region;
                }
                eventType = xpp.next();
            }

            if (TextUtils.isEmpty(data.location)) {
                data.location = town;
            }

            return data;

        } catch (XmlPullParserException e) {
            throw new IOException("Error parsing weather feed XML.", e);
        } finally {
            connection.disconnect();
        }
    }

    private static LocationInfo getLocationInfo(Location location)
            throws IOException, InvalidLocationException {
        LocationInfo li = new LocationInfo();

        // first=tagname (admin1, locality3) second=woeid
        String city = "";
        String county = "";
        String country_iso = "";

        HttpURLConnection connection = openUrlConnection(buildPlaceSearchUrl(location));
        try {
            XmlPullParser xpp = sXmlPullParserFactory.newPullParser();
            xpp.setInput(new InputStreamReader(connection.getInputStream()));

            boolean inCity = false;
            boolean inCounty = false;
            boolean inCountry = false;
            int eventType = xpp.getEventType();

            while (eventType != XmlPullParser.END_DOCUMENT) {
                String tagName = xpp.getName();

                if (eventType == XmlPullParser.START_TAG && "city".equals(tagName)) {
                    inCity = true;
                } else if (eventType == XmlPullParser.TEXT && inCity) {
                    city = xpp.getText();
                }

                if (eventType == XmlPullParser.START_TAG && "county".equals(tagName)) {
                    inCounty = true;
                } else if (eventType == XmlPullParser.TEXT && inCounty) {
                    county = xpp.getText();
                }

                if (eventType == XmlPullParser.START_TAG && "country_code".equals(tagName)) {
                    inCountry = true;
                } else if (eventType == XmlPullParser.TEXT && inCountry) {
                    country_iso = xpp.getText();
                }

                if (eventType == XmlPullParser.END_TAG) {
                    inCity = false;
                    inCounty = false;
                    inCountry = false;
                }

                eventType = xpp.next();
            }

        } catch (XmlPullParserException e) {
            throw new IOException("Error parsing location XML response.", e);
        } finally {
            connection.disconnect();
        }

        if (city.isEmpty())
            city = county;
        if (city.isEmpty() || country_iso.isEmpty())
            return null;

        String primaryWoeid = null;
        String countryCode = "";
        List<Pair<String,String>> alternateWoeids = new ArrayList<Pair<String, String>>();
        connection = openUrlConnection(buildWoeidSearchUrl(city, country_iso));
        try {
            XmlPullParser xpp = sXmlPullParserFactory.newPullParser();
            xpp.setInput(new InputStreamReader(connection.getInputStream()));

            boolean inWoe = false;
            boolean inTown = false;
            int eventType = xpp.getEventType();

            while (eventType != XmlPullParser.END_DOCUMENT) {
                String tagName = xpp.getName();

                if (eventType == XmlPullParser.START_TAG && "woeid".equals(tagName)) {
                    inWoe = true;
                } else if (eventType == XmlPullParser.TEXT && inWoe) {
                    primaryWoeid = xpp.getText();
                }

                if (eventType == XmlPullParser.START_TAG && tagName.startsWith("country")) {
                    for (int i = xpp.getAttributeCount() - 1; i >= 0; i--) {
                        String attrName = xpp.getAttributeName(i);
                        if ("code".equals(attrName)) {
                            countryCode = xpp.getAttributeValue(i);
                        }
                    }
                }

                if (eventType == XmlPullParser.START_TAG &&
                        (tagName.startsWith("locality") || tagName.startsWith("admin"))) {
                    for (int i = xpp.getAttributeCount() - 1; i >= 0; i--) {
                        String attrName = xpp.getAttributeName(i);
                        if ("type".equals(attrName)
                                && "Town".equals(xpp.getAttributeValue(i))) {
                            inTown = true;
                        } else if ("woeid".equals(attrName)) {
                            String woeid = xpp.getAttributeValue(i);
                            if (!TextUtils.isEmpty(woeid)) {
                                alternateWoeids.add(
                                        new Pair<String, String>(tagName, woeid));
                            }
                        }
                    }
                } else if (eventType == XmlPullParser.TEXT && inTown) {
                    li.town = xpp.getText();
                }

                if (eventType == XmlPullParser.END_TAG) {
                    if ("place".equals(tagName) && !countryCode.equalsIgnoreCase(country_iso)) {
                        primaryWoeid = null;
                        countryCode = "";
                        li.town = null;
                        alternateWoeids.clear();
                    }
                    inWoe = false;
                    inTown = false;
                }

                eventType = xpp.next();
            }

            // Add the primary woeid if it was found.
            if (!TextUtils.isEmpty(primaryWoeid)) {
                li.woeids.add(primaryWoeid);
            }

            // Sort by descending tag name to order by decreasing precision
            // (locality3, locality2, locality1, admin3, admin2, admin1, etc.)
            Collections.sort(alternateWoeids, new Comparator<Pair<String, String>>() {
                @Override
                public int compare(Pair<String, String> pair1, Pair<String, String> pair2) {
                    return pair1.first.compareTo(pair2.first);
                }
            });

            for (Pair<String, String> pair : alternateWoeids) {
                li.woeids.add(pair.second);
            }

            if (li.woeids.size() > 0) {
                return li;
            }

            throw new InvalidLocationException();

        } catch (XmlPullParserException e) {
            throw new IOException("Error parsing location XML response.", e);
        } finally {
            connection.disconnect();
        }
    }

    private static String buildWeatherQueryUrl(String woeid) throws MalformedURLException {
        // http://developer.yahoo.com/weather/
        return "http://weather.yahooapis.com/forecastrss?w=" + woeid + "&u=" + sWeatherUnits;
    }

    private static String buildPlaceSearchUrl(Location l) throws MalformedURLException {
        // OpenStreetMap / Mapquest nominatim API
        return "http://open.mapquestapi.com/nominatim/v1/reverse?"
                + "lat=" + l.getLatitude() + "&lon=" + l.getLongitude();
    }

    private static String buildWoeidSearchUrl(String county, String country_iso) throws MalformedURLException {
        // GeoPlanet API
        String query = null;
        try {
            query = URLEncoder.encode("'" + county + "','" + country_iso + "'", "utf-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return null;
        }
        return "http://where.yahooapis.com/v1/places.q(" + query + ");count=5"
                + "?appid=kGO140TV34HVTae_DDS93fM_w3AJmtmI23gxUFnHKWyrOGcRzoFjYpw8Ato6BxhvbTg-";
    }

    private static class LocationInfo {
        // Sorted by decreasing precision
        // (point of interest, locality3, locality2, locality1, admin3, admin2, admin1, etc.)
        List<String> woeids = new ArrayList<String>();
        String town;
    }

    public static HttpURLConnection openUrlConnection(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setUseCaches(false);
        conn.setChunkedStreamingMode(0);
        conn.setRequestProperty("User-Agent", "DashClock/0.0");
        conn.connect();
        return conn;
    }

    public static class InvalidLocationException extends Exception {
        public InvalidLocationException() {
        }

        public InvalidLocationException(String detailMessage) {
            super(detailMessage);
        }

        public InvalidLocationException(String detailMessage, Throwable throwable) {
            super(detailMessage, throwable);
        }

        public InvalidLocationException(Throwable throwable) {
            super(throwable);
        }
    }
}
