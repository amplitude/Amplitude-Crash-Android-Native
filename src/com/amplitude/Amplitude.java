package com.amplitude;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

import org.apache.http.HttpStatus;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.util.Log;

public class Amplitude {

	private static final String TAG = "Amplitude";
	public static final String PACKAGE_NAME = Amplitude.class.getPackage()
			.getName();

	public static final String UPLOAD_HOST = "https://crash.amplitude.com";
	public static final String UPLOAD_PATH = "/crash";
	public static final String UPLOAD_URL = UPLOAD_HOST + UPLOAD_PATH;
	public static final String SPACER = "--";
	public static final String BOUNDARY = "---AmplitudeBoundaryL_o___";
	public static final String NEW_LINE = "\r\n";
	public static final int MAX_BUFFER_SIZE = 4096;

	private static UploadThread instance = new UploadThread();
	static {
		// TODO: Make this thread ephemeral, or use SingleThreadExecutor
		instance.start();
	}

	private static final String PLATFORM = "android";

	private Amplitude() {}

	public static void initCrashReporting(final Context context,
			final String crashDumpDir, final String apiKey, final String version) {
		Amplitude.initCrashReporting(context, crashDumpDir, apiKey, version,
				null);
	}

	public static void initCrashReporting(final Context context,
			final String crashDumpDir, final String apiKey,
			final String version, final JSONObject extras) {
		try {
			if (apiKey == null) {
				Log.e(TAG, "No API Key provided");
				return;
			}
			final Data data = new Data(
					apiKey,
					Data.getAppName(context),
					version != null ? version : Data.getAppVersionName(context),
					Data.getAppVersionCode(context), PLATFORM,
					Build.VERSION.SDK_INT, Build.VERSION.RELEASE, Build.BRAND,
					Build.MANUFACTURER, Build.MODEL, Locale.getDefault()
							.getDisplayCountry(), Locale.getDefault()
							.getDisplayLanguage(), Data.getCarrier(context),
					extras);

			UploadThread.post(new Runnable() {
				@Override
				public void run() {

					try {

						File f = new File(crashDumpDir);
						if (!f.isDirectory()) {
							if (!f.mkdirs()) {
								Log.w(TAG, "Invalid crash directory "
										+ crashDumpDir);
								return;
							}
						}
						File[] files = f.listFiles(new FileFilter() {
							@Override
							public boolean accept(File file) {
								return file.isFile();
							}
						});
						if (files != null) {
							Arrays.sort(files, new Comparator<File>() {
								@Override
								public int compare(File lhs, File rhs) {
									long lhsLastModified = lhs.lastModified();
									long rhsLastModified = rhs.lastModified();
									if (lhsLastModified < rhsLastModified) {
										return 1;
									} else if (lhsLastModified > rhsLastModified) {
										return -1;
									} else {
										return 0;
									}
								}
							});
							for (File file : files) {
								try {
									uploadFile(context, file, UPLOAD_URL, data);
								} catch (IOException e) {
									Log.e(TAG, e.getMessage());
								} catch (JSONException e) {
									Log.e(TAG, e.getMessage());
								}
							}
						}
						// Empty the directory
						for (File file : f.listFiles()) {
							if (file.isFile()) {
								file.delete();
							}
						}
					} catch (Exception e) {
						Log.e(TAG, e.getMessage());
					}
				}
			});
		} catch (Exception e) {
			Log.e(TAG, e.getMessage());
		}
	}

	private static void uploadFile(final Context context, File f,
			String endpoint, Data data) throws IOException, JSONException {
		Log.d(TAG, "Uploading " + f.getName());
		URL url = new URL(endpoint);
		int bytesRead;
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setDoOutput(true);
		conn.setDoInput(true);
		conn.setUseCaches(false);
		conn.setRequestProperty("Content-Type",
				"multipart/form-data; boundary=" + BOUNDARY);

		String contentDisposition = "Content-Disposition: form-data; name=\"minidump\"; filename=\""
				+ f.getName() + "\"";
		String contentType = "Content-Type: application/octet-stream";

		DataOutputStream dos = null;
		FileInputStream fis = null;
		BufferedReader br = null;
		long crashtime = f.lastModified();

		JSONObject extras = data.asJSONObject();
		extras.put("crashtime", crashtime);

		try {
			dos = new DataOutputStream(conn.getOutputStream());
			fis = new FileInputStream(f);
			dos.writeBytes(SPACER + BOUNDARY + NEW_LINE);
			dos.writeBytes("Content-Disposition: form-data; name=\"apiKey\""
					+ NEW_LINE);
			dos.writeBytes(NEW_LINE);
			dos.writeBytes(data.apiKey + NEW_LINE);

			dos.writeBytes(SPACER + BOUNDARY + NEW_LINE);
			dos.writeBytes("Content-Disposition: form-data; name=\"appName\""
					+ NEW_LINE);
			dos.writeBytes(NEW_LINE);
			dos.write(data.appName.getBytes("UTF-8"));
			dos.writeBytes(NEW_LINE);

			dos.writeBytes(SPACER + BOUNDARY + NEW_LINE);
			dos.writeBytes("Content-Disposition: form-data; name=\"version\""
					+ NEW_LINE);
			dos.writeBytes(NEW_LINE);
			dos.write(data.version.getBytes("UTF-8"));
			dos.writeBytes(NEW_LINE);

			dos.writeBytes(SPACER + BOUNDARY + NEW_LINE);
			dos.writeBytes("Content-Disposition: form-data; name=\"versionCode\""
					+ NEW_LINE);
			dos.writeBytes(NEW_LINE);
			dos.writeBytes(data.versionCode + NEW_LINE);

			dos.writeBytes(SPACER + BOUNDARY + NEW_LINE);
			dos.writeBytes("Content-Disposition: form-data; name=\"crashtime\""
					+ NEW_LINE);
			dos.writeBytes(NEW_LINE);
			dos.writeBytes(crashtime + NEW_LINE);

			dos.writeBytes(SPACER + BOUNDARY + NEW_LINE);
			dos.writeBytes("Content-Disposition: form-data; name=\"extras\""
					+ NEW_LINE);
			dos.writeBytes(NEW_LINE);
			dos.write(extras.toString().getBytes("UTF-8"));
			dos.writeBytes(NEW_LINE);

			dos.writeBytes(SPACER + BOUNDARY + NEW_LINE);
			dos.writeBytes("Content-Disposition: form-data; name=\"uploadtime\""
					+ NEW_LINE);
			dos.writeBytes(NEW_LINE);
			dos.writeBytes(System.currentTimeMillis() + NEW_LINE);

			dos.writeBytes(SPACER + BOUNDARY + NEW_LINE);
			dos.writeBytes(contentDisposition + NEW_LINE);
			dos.writeBytes(contentType + NEW_LINE);
			dos.writeBytes(NEW_LINE);
			byte[] buffer = new byte[MAX_BUFFER_SIZE];
			while ((bytesRead = fis.read(buffer)) != -1) {
				dos.write(buffer, 0, bytesRead);
			}
			dos.writeBytes(NEW_LINE);
			dos.writeBytes(SPACER + BOUNDARY + SPACER);
			dos.flush();

			int responseCode = conn.getResponseCode();
			if (responseCode != HttpStatus.SC_OK) {
				Log.w(TAG,
						responseCode + " Error: " + conn.getResponseMessage());
				return;
			}

			br = new BufferedReader(
					new InputStreamReader(conn.getInputStream()));
			StringBuilder sb = new StringBuilder();
			String line = null;
			while ((line = br.readLine()) != null) {
				sb.append(line + "\n");
			}
			Log.d(TAG, "Sucessfully uploaded " + f.getName());
		} finally {
			safeClose(dos);
			safeClose(fis);
			safeClose(br);
		}

	}

	private static void safeClose(Closeable stream) {
		try {
			if (stream != null) {
				stream.close();
			}
		} catch (IOException e) {
			// stream was already closed!
		}
	}

	private static class Data {
		final private String apiKey;
		final private String appName;
		final private String version;
		final private int versionCode;
		final private String platform;
		final private int platform_sdk;
		final private String platform_release;
		final private String device_brand;
		final private String device_manufacturer;
		final private String device_model;
		final private String country;
		final private String language;
		final private String carrier;
		final private JSONObject extras;

		private Data(String apiKey, String appName, String version,
				int versionCode, String platform, int platform_sdk,
				String platform_release, String device_brand,
				String device_manufacturer, String device_model,
				String country, String language, String carrier,
				JSONObject extras) {
			this.apiKey = apiKey;
			this.appName = appName;
			this.version = version;
			this.versionCode = versionCode;
			this.platform = platform;
			this.platform_sdk = platform_sdk;
			this.platform_release = platform_release;
			this.device_brand = device_brand;
			this.device_manufacturer = device_manufacturer;
			this.device_model = device_model;
			this.country = country;
			this.language = language;
			this.carrier = carrier;
			this.extras = extras;
		}

		private JSONObject asJSONObject() throws JSONException {
			JSONObject json = new JSONObject();
			json.put("app_name", appName);
			json.put("version", version);
			json.put("version_code", versionCode);
			json.put("platform", platform);
			json.put("platform_sdk", platform_sdk);
			json.put("platform_release", platform_release);
			json.put("device_brand", device_brand);
			json.put("device_manufacturer", device_manufacturer);
			json.put("device_model", device_model);
			json.put("country", country);
			json.put("language", language);
			json.put("carrier", carrier);
			json.put("extras", extras);
			return json;
		}

		private static String getAppName(Context context) {
			PackageManager packageManager = context.getApplicationContext()
					.getPackageManager();
			ApplicationInfo info;
			try {
				info = packageManager.getApplicationInfo(
						context.getPackageName(), 0);
			} catch (NameNotFoundException e) {
				info = null;
			}
			String appName = info != null ? (String) packageManager
					.getApplicationLabel(info) : null;
			return appName != null ? appName : "";
		}

		private static String getAppVersionName(Context context) {
			PackageInfo info;
			try {
				info = context
						.getApplicationContext()
						.getPackageManager()
						.getPackageInfo(
								context.getApplicationContext()
										.getPackageName(), 0);
			} catch (NameNotFoundException n) {
				info = null;
			}
			String versionName = info != null ? info.versionName : null;
			return versionName != null ? versionName : "";
		}

		private static int getAppVersionCode(Context context) {
			PackageInfo info;
			try {
				info = context
						.getApplicationContext()
						.getPackageManager()
						.getPackageInfo(
								context.getApplicationContext()
										.getPackageName(), 0);
			} catch (NameNotFoundException n) {
				info = null;
			}
			return info != null ? info.versionCode : -1;
		}

		private static String getCarrier(Context context) {
			TelephonyManager manager = (TelephonyManager) context
					.getSystemService(Context.TELEPHONY_SERVICE);
			return manager.getNetworkOperatorName();
		}
	}

	private static class UploadThread extends Thread {

		private Handler handler;

		private UploadThread() {}

		@Override
		public void run() {
			Looper.prepare();
			synchronized (instance) {
				handler = new Handler();
				instance.notifyAll();
			}
			Looper.loop();
		}

		static void post(Runnable r) {
			waitForHandlerInitialization();
			instance.handler.post(r);
		}

		private static void waitForHandlerInitialization() {
			while (instance.handler == null) {
				synchronized (instance) {
					try {
						if (instance.handler == null)
							instance.wait();
					} catch (InterruptedException e) {
					}
				}
			}
		}
	}
}
