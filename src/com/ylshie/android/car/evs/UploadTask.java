package com.ylshie.android.car.evs;
import android.os.AsyncTask;
import java.net.HttpURLConnection;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.DataOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.BufferedInputStream;
import java.lang.StringBuilder;
import java.lang.Exception;
import java.net.URL;
import android.net.Uri;
import android.util.Log;
import android.util.Base64;
import java.io.OutputStream;
import java.io.IOException;

class UploadTask extends AsyncTask<Object, String, String> {
    private static final String TAG = UploadTask.class.getSimpleName();
    String file_name = "";
    String target_url = "http://webkul.com/upload";
    Uri uri = null; // to check
    String username;
    String password;
    UploadTask(String url,String id,String secret,Uri uriFile) {
        target_url = url;
        username = id;
        password = secret;
        uri = uriFile;
        //uri = null;
    }
    class MyOutputStream {
        DataOutputStream st = null;
        public MyOutputStream (OutputStream out) {
            //super(out);
            st = new DataOutputStream(out);
        }
        public void write (byte[] b, int off, int len) throws IOException {
            st.write(b, off, len);
            String trace = new String(b, "UTF-8");
            Log.d(TAG, "[Arthur](" + trace.length() + ") >>>>>" + trace);
        }
        public final void writeBytes (String s) throws IOException {
            st.writeBytes(s);
            Log.d(TAG, "[Arthur](" + s.length() + ") >>>>>" + s);
        }
        void flush() throws IOException {
            st.flush();
        }
        void close() throws IOException {
            st.close();
        }
    }
    @Override
    protected String doInBackground(Object[] params) {
        try {
            String lineEnd = "\r\n";
            String twoHyphens = "--";
            String boundary = "*****";
            int bytesRead, bytesAvailable, bufferSize;
            byte[] buffer;
            int maxBufferSize = 1024 * 1024;
            //todo change URL as per client ( MOST IMPORTANT )
            URL url = new URL(target_url);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            String userCredentials = username + ":" + password;
            String basicAuth = "Basic " + new String(Base64.encodeToString(userCredentials.getBytes(), Base64.DEFAULT));

            connection.setRequestProperty ("Authorization", basicAuth);
            // Allow Inputs &amp; Outputs.
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setUseCaches(false);

            // Set HTTP method to POST.
            connection.setRequestMethod("POST");

            connection.setRequestProperty("Connection", "Keep-Alive");
            connection.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);
            FileInputStream fileInputStream = null;
            //DataOutputStream outputStream;
            MyOutputStream outputStream;
            //outputStream = new DataOutputStream(connection.getOutputStream());
            outputStream = new MyOutputStream(connection.getOutputStream());
            outputStream.writeBytes(twoHyphens + boundary + lineEnd);

        //  field
            String[] fields = {"vin", "timestamp", "eventType", "file"};
            String[] values = {"testid", "1721963335000", "A", "file"};
        //  VIN
            outputStream.writeBytes("Content-Disposition: form-data; name=\""+ fields[0] + "\""+ lineEnd);
            outputStream.writeBytes(lineEnd);
            outputStream.writeBytes(values[0]);
            outputStream.writeBytes(lineEnd);
            outputStream.writeBytes(twoHyphens + boundary + lineEnd);

        //  timestamp
            outputStream.writeBytes("Content-Disposition: form-data; name=\""+ fields[1] + "\""+ lineEnd);
            outputStream.writeBytes(lineEnd);
            outputStream.writeBytes(values[1]);
            outputStream.writeBytes(lineEnd);
            outputStream.writeBytes(twoHyphens + boundary + lineEnd);

        //  eventType
            outputStream.writeBytes("Content-Disposition: form-data; name=\""+ fields[2] + "\""+ lineEnd);
            outputStream.writeBytes(lineEnd);
            outputStream.writeBytes(values[2]);
            outputStream.writeBytes(lineEnd);
            if (uri != null) {
                outputStream.writeBytes(twoHyphens + boundary + lineEnd);
            }   

        //  file
            if (uri != null) {
                String filename = uri.getLastPathSegment();
                Log.d(TAG, "[Arthur] upload file is " + filename);
                outputStream.writeBytes("Content-Disposition: form-data; name=\""+ fields[3] + "\"; filename=\"" + filename +"\"" + lineEnd);
                outputStream.writeBytes("Content-Type: video/mp4" + lineEnd);
                outputStream.writeBytes(lineEnd);
            
                wtireFile1(outputStream, uri);
                outputStream.writeBytes(lineEnd);
            } else {
        //  Multi End
                Log.d(TAG, "[Arthur] No File input");
            }
            outputStream.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);

            // Responses from the server (code and message)
            int serverResponseCode = connection.getResponseCode();
            String message = connection.getResponseMessage();
            String result = null;
            Log.d(TAG, "[Arthur] response code=" + serverResponseCode + " msg=" + message);
            //if (serverResponseCode == 200) {
            {
                StringBuilder s_buffer = new StringBuilder();
                InputStream res = (serverResponseCode == 200)? connection.getInputStream(): connection.getErrorStream();
                InputStream is = new BufferedInputStream(res);
                BufferedReader br = new BufferedReader(new InputStreamReader(is));
                String inputLine;
                while ((inputLine = br.readLine()) != null) {
                    s_buffer.append(inputLine);
                }
                result = s_buffer.toString();
            }
            //Log.d(TAG, "[Arthur] response code=" + serverResponseCode + " res=" + result);

            if (fileInputStream != null) {
                fileInputStream.close();
            }
            outputStream.flush();
            outputStream.close();
            Log.d(TAG, "[Arthur] getInputStream return");

            if (result != null) {
                Log.d(TAG, "[Arthur] result_for upload " + result);
            // [Arthur]
            //    file_name = getDataFromInputStream(result, "file_name");
            }
        } catch (Exception e) {
            Log.d(TAG, "[Arthur] Upload Exception");
            e.printStackTrace();
        }
        return file_name;
    }
    static protected void wtireFile1(MyOutputStream outputStream, Uri uri) {
        int bytesRead, bytesAvailable, bufferSize;
        byte[] buffer;
        int maxBufferSize = 1024 * 1024;

        try {
            FileInputStream fileInputStream = new FileInputStream(uri.getPath());
            bytesAvailable = fileInputStream.available();
            bufferSize = Math.min(bytesAvailable, maxBufferSize);
            buffer = new byte[bufferSize];

            // Read file
            bytesRead = fileInputStream.read(buffer, 0, bufferSize);

            Log.d(TAG, "[Arthur] Read size=" + bufferSize + " got=" + bytesRead);
            String buffer64 = new String(Base64.encodeToString(buffer, Base64.DEFAULT));
            Log.d(TAG, "[Arthur] Write size=" + buffer64.length());
            //outputStream.write(buffer64.getBytes("UTF-8"), 0, buffer64.length());
            outputStream.writeBytes(buffer64);
            /*
            while (bytesRead > 0) {
                Log.d(TAG, "[Arthur] Write size=" + bufferSize);
                outputStream.write(buffer, 0, bufferSize);
                bytesAvailable = fileInputStream.available();
                bufferSize = Math.min(bytesAvailable, maxBufferSize);
                bytesRead = fileInputStream.read(buffer, 0, bufferSize);
            }
            */
        } catch (Exception e) {
            Log.d(TAG, "[Arthur] Upload Exception");
            e.printStackTrace();
        }
    }
    static protected void wtireFile0(MyOutputStream outputStream, Uri uri) {
        int bytesRead, bytesAvailable, bufferSize;
        byte[] buffer;
        int maxBufferSize = 1024 * 1024;

        try {
            FileInputStream fileInputStream = new FileInputStream(uri.getPath());
            bytesAvailable = fileInputStream.available();
            bufferSize = Math.min(bytesAvailable, maxBufferSize);
            buffer = new byte[bufferSize];

            // Read file
            bytesRead = fileInputStream.read(buffer, 0, bufferSize);

            while (bytesRead > 0) {
                Log.d(TAG, "[Arthur] Write size=" + bufferSize);
                outputStream.write(buffer, 0, bufferSize);
                bytesAvailable = fileInputStream.available();
                bufferSize = Math.min(bytesAvailable, maxBufferSize);
                bytesRead = fileInputStream.read(buffer, 0, bufferSize);
            }
        } catch (Exception e) {
            Log.d(TAG, "[Arthur] Upload Exception");
            e.printStackTrace();
        }
    }
}

