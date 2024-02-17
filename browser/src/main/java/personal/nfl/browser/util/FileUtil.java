package personal.nfl.browser.util;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;

import java.io.File;

public class FileUtil {
    public static File getFileByUri(Context context, Uri uri) {
        Context contextImpl = context.getApplicationContext() == null ? context : context.getApplicationContext();
        String path = null;

        // 4.2.2以后
        String[] proj = {MediaStore.Images.Media.DATA};
        Cursor cursor = contextImpl.getContentResolver().query(uri, proj, null, null, null);
        if (cursor.moveToFirst()) {
            int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
            path = cursor.getString(columnIndex);
        }
        cursor.close();

        return new File(path);

//        return null;
    }

    // app 沙箱中的目录，如：/storage/emulated/0/Android/data/包名/files/Download
    // public static String DOWNLOAD_PATH = getDownloadPath();
    // sdcard 下的 download 目录
    public static String PUBLIC_DOWNLOAD_PATH = "";

    public static String getImageRealPathFromURI(Uri contentURI, Context context) {
        String result = "";
        int idx = 0;
        Cursor cursor = context.getContentResolver().query(contentURI, null, null, null, null);
        if (cursor == null) { // Source is Dropbox or other similar local file path
            result = contentURI.getPath();
        } else {
            cursor.moveToFirst();
            String document_id = cursor.getString(0);
            if (document_id.contains(":")) {
                String id = document_id.split(":")[1];
                String[] column = {MediaStore.Images.Media.DATA};
                String sel = MediaStore.Images.Media._ID + "=?";
                Cursor cursorByColon = context.getContentResolver().
                        query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                column, sel, new String[]{id}, null);
                int columnIndex = cursorByColon.getColumnIndex(column[0]);
                if (cursorByColon.moveToFirst()) {
                    result = cursorByColon.getString(columnIndex);
                }
                cursorByColon.close();
            } else {
                idx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                result = cursor.getString(idx);
            }
            cursor.close();
        }
        return result;
    }

    public static void openAlbum(Activity activity) {
        String IMAGE_TYPE = "image/*";
//        int IMAGE_REQUEST_CODE = 0x102;
        Intent intent = new Intent();
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(IMAGE_TYPE);
        if (Build.VERSION.SDK_INT < 19) {
            intent.setAction(Intent.ACTION_GET_CONTENT);
        } else {
            intent.setAction(Intent.ACTION_OPEN_DOCUMENT);
        }
        // activity.startActivityForResult(intent, IMAGE_CODE);
        activity.startActivity(intent);

    }

    public static void openAssignFolder(Activity activity, String path) {
        Context context = activity;
        File file = new File(path);
        if (null == file || !file.exists()) {
            // ToastUtil.showShort(path + "不存在");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
//            StrictMode.VmPolicy.Builder builder = new StrictMode.VmPolicy.Builder();
//            StrictMode.setVmPolicy(builder.build());
//            builder.detectFileUriExposure();
        }
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        // intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);


        // intent.setAction("android.intent.action.VIEW");
        Uri fileUri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            //申请权限
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            //getUriForFile的第二个参数就是Manifest中的authorities
            fileUri = FileProvider.getUriForFile(context,
                    activity.getApplicationInfo().packageName + ".Download", file);
        } else {
            fileUri = Uri.fromFile(file);
        }

        String extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(file).toString());
        String mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        intent.setDataAndType(fileUri, mimeType);
        // intent.setDataAndType(fileUri, "file/*");


        try {
            // BaseApplication.application.startActivity(intent);
            activity.startActivityForResult(Intent.createChooser(intent, "选择浏览工具"), 100);
        } catch (ActivityNotFoundException e) {
            // LogUtil.i(ExceptionUtil.getExceptionTraceString(e));
        }
    }

    /**
     * 获取文件的 MimeType
     *
     * @param file
     * @return
     */
    public static String getMimeType(File file) {
        String extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(file).toString());
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
    }

//    private static String getDownloadPath() {
//        return TextUtils.isEmpty(DOWNLOAD_PATH) ?
//                BaseApplication.application.getExternalFilesDir(DIRECTORY_DOWNLOADS).getAbsolutePath() :
//                DOWNLOAD_PATH;
//    }

    private static void openFile(Context context, File f) {
        Intent myIntent = new Intent(Intent.ACTION_VIEW);
        String extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(f).toString());
        String mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        myIntent.setDataAndType(Uri.fromFile(f), mimeType);
        context.startActivity(myIntent);
    }

    public static String getRealPath(Context context, Uri uri) {
        String realPath = "";
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            Cursor cursor = context.getContentResolver().query(uri, new String[]{MediaStore.Images.ImageColumns.DATA},
                    null, null, null);
            if (null != cursor) {
                if (cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATA);
                    if (index > -1) {
                        realPath = cursor.getString(index);
                        // LogUtil.i("真实路径：" + realPath);
                    }
                }
                cursor.close();
            }
        }
        return realPath;
    }

    public static String getRealPath(String path) {
        String realPath = "";
        if (null != path) {

        }
        return realPath;
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    public static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    public static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    public static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

    /**
     * Get the value of the data column for this Uri. This is useful for
     * MediaStore Uris, and other file-based ContentProviders.
     *
     * @param context       The context.
     * @param uri           The Uri to query.
     * @param selection     (Optional) Filter used in the query.
     * @param selectionArgs (Optional) Selection arguments used in the query.
     * @return The value of the _data column, which is typically a file path.
     */
    public static String getDataColumn(Context context, Uri uri, String selection,
                                       String[] selectionArgs) {

        Cursor cursor = null;
        final String column = "_data";
        final String[] projection = {
                column
        };

        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs,
                    null);
            if (cursor != null && cursor.moveToFirst()) {
                final int column_index = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(column_index);
            }
        } finally {
            if (cursor != null)
                cursor.close();
        }
        return null;
    }

    /**
     * Get a file path from a Uri. This will get the the path for Storage Access
     * Framework Documents, as well as the _data field for the MediaStore and
     * other file-based ContentProviders.
     *
     * @param context The context.
     * @param uri     The Uri to query.
     * @author paulburke
     */
    @SuppressLint("NewApi")
    public static String getPath(final Context context, final Uri uri) {

        final boolean isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;

        // DocumentProvider
        if (isKitKat && DocumentsContract.isDocumentUri(context, uri)) {
            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                if ("primary".equalsIgnoreCase(type)) {
                    return Environment.getExternalStorageDirectory() + "/" + split[1];
                }

                // TODO handle non-primary volumes
            }
            // DownloadsProvider
            else if (isDownloadsDocument(uri)) {

                final String id = DocumentsContract.getDocumentId(uri);
                final Uri contentUri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"), Long.valueOf(id));

                return getDataColumn(context, contentUri, null, null);
            }
            // MediaProvider
            else if (isMediaDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                Uri contentUri = null;
                if ("image".equals(type)) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }

                final String selection = "_id=?";
                final String[] selectionArgs = new String[]{
                        split[1]
                };

                return getDataColumn(context, contentUri, selection, selectionArgs);
            }
        }
        // MediaStore (and general)
        else if ("content".equalsIgnoreCase(uri.getScheme())) {
            return getDataColumn(context, uri, null, null);
        }
        // File
        else if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }

        return null;
    }
}
