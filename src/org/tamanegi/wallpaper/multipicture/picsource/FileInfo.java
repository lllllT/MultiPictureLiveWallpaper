package org.tamanegi.wallpaper.multipicture.picsource;

import java.util.Collections;
import java.util.Comparator;

import android.net.Uri;

public class FileInfo
{
    private Uri uri;
    private String full_name;
    private long date;
    private int orientation;

    public Uri getUri()
    {
        return uri;
    }

    public void setUri(Uri uri)
    {
        this.uri = uri;
    }

    public String getFullName()
    {
        return full_name;
    }

    public void setFullName(String fullName) {
        this.full_name = fullName;
    }

    public long getDate()
    {
        return date;
    }

    public void setDate(long date)
    {
        this.date = date;
    }

    public int getOrientation()
    {
        return orientation;
    }

    public void setOrientation(int orientation)
    {
        this.orientation = orientation;
    }

    public boolean equalsUri(FileInfo val)
    {
        return (val != null && uri.equals(val.uri));
    }

    public static Comparator<FileInfo> getComparator(OrderType type)
    {
        if(type == OrderType.name_asc) {
            return new FileInfoNameComparator();
        }
        if(type == OrderType.name_desc) {
            return Collections.reverseOrder(new FileInfoNameComparator());
        }
        if(type == OrderType.date_asc) {
            return new FileInfoDateComparator();
        }
        if(type == OrderType.date_desc) {
            return Collections.reverseOrder(new FileInfoDateComparator());
        }
        return null;
    }

    private static class FileInfoNameComparator implements Comparator<FileInfo>
    {
        @Override
        public int compare(FileInfo v1, FileInfo v2)
        {
            return v1.full_name.compareTo(v2.full_name);
        }
    }

    private static class FileInfoDateComparator implements Comparator<FileInfo>
    {
        @Override
        public int compare(FileInfo v1, FileInfo v2)
        {
            return (v1.date < v2.date ? -1 :
                    v1.date > v2.date ?  1 :
                    0);
        }
    }
}