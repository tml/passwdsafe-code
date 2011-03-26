package com.jefftharris.passwdsafe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class PasswdHistory
{
    public static class Entry implements Comparable<Entry>
    {
        private final Date itsDate;
        private final String itsPasswd;

        public Entry(Date date, String passwd)
        {
            itsDate = date;
            itsPasswd = passwd;
        }

        public Date getDate()
        {
            return itsDate;
        }

        public String getPasswd()
        {
            return itsPasswd;
        }

        public int compareTo(Entry arg0)
        {
            // Sort descending
            return -itsDate.compareTo(arg0.itsDate);
        }

        @Override
        public String toString()
        {
            StringBuilder str = new StringBuilder(itsPasswd);
            str.append(" [").append(itsDate).append("]");
            return str.toString();
        }
    }

    private boolean itsIsEnabled;
    private int itsMaxSize;
    // Sorted with newest entry first
    private List<Entry> itsPasswds = new ArrayList<Entry>();

    public PasswdHistory(String historyStr)
        throws IllegalArgumentException
    {
        int historyLen = historyStr.length();
        if (historyLen < 5) {
            throw new IllegalArgumentException(
                "Field length (" + historyLen + ") too short: " + 5);
        }

        itsIsEnabled = historyStr.charAt(0) != '0';
        itsMaxSize = Integer.parseInt(historyStr.substring(1, 3), 16);
        if (itsMaxSize > 255) {
            throw new IllegalArgumentException(
                "Invalid max size: " + itsMaxSize);
        }

        int numEntries = Integer.parseInt(historyStr.substring(3, 5), 16);
        if (numEntries > 255) {
            throw new IllegalArgumentException(
                "Invalid numEntries: " + numEntries);
        }

        int pos = 5;
        while (pos < historyLen) {
            if (pos + 12 >= historyLen) {
                throw new IllegalArgumentException(
                    "Field length (" + historyLen + ") too short: " +
                    (pos + 12));
            }

            long date = Long.parseLong(historyStr.substring(pos, pos + 8), 16);
            int passwdLen =
                Integer.parseInt(historyStr.substring(pos + 8, pos + 12), 16);
            pos += 12;

            if (pos + passwdLen > historyLen) {
                throw new IllegalArgumentException(
                    "Field length (" + historyLen + ") too short: " +
                    (pos + passwdLen));
            }

            String passwd = historyStr.substring(pos, pos + passwdLen);
            itsPasswds.add(new Entry(new Date(date * 1000L), passwd));
            pos += passwdLen;
        }
        Collections.sort(itsPasswds);
    }

    public boolean isEnabled()
    {
        return itsIsEnabled;
    }

    public int getMaxSize()
    {
        return itsMaxSize;
    }

    public List<Entry> getPasswds()
    {
        return itsPasswds;
    }

    public void addPasswd(String passwd)
    {
        if (itsIsEnabled && (itsMaxSize > 0)) {
            if (itsPasswds.size() == itsMaxSize) {
                // Remove oldest
                itsPasswds.remove(itsPasswds.size() - 1);
            }
            itsPasswds.add(new Entry(new Date(), passwd));
            Collections.sort(itsPasswds);
        }
    }

    @Override
    public String toString()
    {
        StringBuilder strbld = new StringBuilder();
        String str = String.format("%1d%02x%02x", isEnabled() ? 1 : 0,
                                   itsMaxSize, itsPasswds.size());
        strbld.append(str);

        for (Entry entry : itsPasswds) {
            String passwd = entry.getPasswd();
            str = String.format("%08x%04x",
                                (int)(entry.getDate().getTime() / 1000),
                                passwd.length());
            strbld.append(str);
            strbld.append(passwd);
        }

        return strbld.toString();
    }
}