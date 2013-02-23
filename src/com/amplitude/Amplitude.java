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

import org.apache.http.HttpStatus;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class Amplitude {

	private static final String TAG = "Amplitude";
	public static final String PACKAGE_NAME = Amplitude.class.getPackage().getName();
	
	public static final String UPLOAD_HOST = "https://crash.amplitude.com";
	public static final String UPLOAD_PATH = "/crash";
	public static final String UPLOAD_URL = UPLOAD_HOST + UPLOAD_PATH;
	public static final String SPACER = "--";
	public static final String BOUNDARY = "---AmplitudeBoundaryL_o___";
	public static final String NEW_LINE = "\r\n";
	public static final int MAX_BUFFER_SIZE = 4096;

	private static UploadThread instance = new UploadThread();
	static {
		instance.start();
	}

	private static String apiKey;
	private static String appName;
	private static String version;
	private static int versionCode;

	private Amplitude() {}
	
	public static void initCrashReporting(final Context context,
			final String crashDumpDir, final String apiKey, final String version) {
		try {
			if (apiKey == null) {
				Log.e(TAG, "No app id provided");
				return;
			}
			Amplitude.appName = getAppName(context);
			Amplitude.apiKey = apiKey;
			Amplitude.version = version != null ? version : getAppVersionName(context);
			Amplitude.versionCode = getAppVersionCode(context);
			UploadThread.post(new Runnable() {
				@Override
				public void run() {
	
					try {
					
						// Get last successful upload time
			            SharedPreferences sharedPreferences = context.getSharedPreferences(
			                PACKAGE_NAME + "." + context.getPackageName(), Context.MODE_PRIVATE);
			            final long lastSuccessfulUploadTime = sharedPreferences.getLong(PACKAGE_NAME + ".lastSuccessfulUploadTime", 0);
			            
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
								return file.isFile() && file.lastModified() > lastSuccessfulUploadTime;
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
									uploadFile(context, file, UPLOAD_URL);
								} catch (IOException e) {
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

	private static void uploadFile(final Context context, File f, String endpoint) throws IOException {
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
		try {
			dos = new DataOutputStream(conn.getOutputStream());
			fis = new FileInputStream(f);
			dos.writeBytes(SPACER + BOUNDARY + NEW_LINE);
			dos.writeBytes("Content-Disposition: form-data; name=\"apiKey\""
					+ NEW_LINE);
			dos.writeBytes(NEW_LINE);
			dos.writeBytes(Amplitude.apiKey + NEW_LINE);

			dos.writeBytes(SPACER + BOUNDARY + NEW_LINE);
			dos.writeBytes("Content-Disposition: form-data; name=\"appName\""
					+ NEW_LINE);
			dos.writeBytes(NEW_LINE);
			dos.writeBytes(Amplitude.appName + NEW_LINE);

			dos.writeBytes(SPACER + BOUNDARY + NEW_LINE);
			dos.writeBytes("Content-Disposition: form-data; name=\"version\""
					+ NEW_LINE);
			dos.writeBytes(NEW_LINE);
			dos.writeBytes(Amplitude.version + NEW_LINE);

			dos.writeBytes(SPACER + BOUNDARY + NEW_LINE);
			dos.writeBytes("Content-Disposition: form-data; name=\"versionCode\""
					+ NEW_LINE);
			dos.writeBytes(NEW_LINE);
			dos.writeBytes(Amplitude.versionCode + NEW_LINE);

			dos.writeBytes(SPACER + BOUNDARY + NEW_LINE);
			dos.writeBytes("Content-Disposition: form-data; name=\"crashtime\""
					+ NEW_LINE);
			dos.writeBytes(NEW_LINE);
			dos.writeBytes(crashtime + NEW_LINE);

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
				Log.w(TAG, responseCode + " Error: " + conn.getResponseMessage());
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
			
			// Save last successful upload time
            SharedPreferences sharedPreferences = context.getSharedPreferences(
                PACKAGE_NAME + "." + context.getPackageName(), Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putLong(PACKAGE_NAME + ".lastSuccessfulUploadTime", crashtime);
            editor.commit();
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

	private static String getAppName(Context context) {
		PackageManager packageManager = context.getApplicationContext()
				.getPackageManager();
		ApplicationInfo info;
		try {
			info = packageManager.getApplicationInfo(context.getPackageName(),
					0);
		} catch (NameNotFoundException e) {
			info = null;
		}
		String appName = info != null ? (String) packageManager.getApplicationLabel(info) : null;
		return appName != null ? appName : "";
	}

	private static String getAppVersionName(Context context) {
		PackageInfo info;
		try {
			info = context
					.getApplicationContext()
					.getPackageManager()
					.getPackageInfo(
							context.getApplicationContext().getPackageName(), 0);
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
							context.getApplicationContext().getPackageName(), 0);
		} catch (NameNotFoundException n) {
			info = null;
		}
		return info != null ? info.versionCode : -1;
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
						instance.wait();
					} catch (InterruptedException e) {
					}
				}
			}
		}
	}
}
