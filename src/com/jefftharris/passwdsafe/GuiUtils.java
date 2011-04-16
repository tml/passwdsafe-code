/*
 * Copyright (©) 2010-2011 Jeff Harris <jefftharris@gmail.com>
 * All rights reserved. Use of the code is allowed under the
 * Artistic License 2.0 terms, as specified in the LICENSE file
 * distributed with this code, or available from
 * http://www.opensource.org/licenses/artistic-license-2.0.php
 */
package com.jefftharris.passwdsafe;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.HashMap;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.Spinner;
import android.widget.TextView;

public final class GuiUtils
{
    private static final String PASSWD = "passwd";
    private static final String DATE = "date";

    public static String getTextViewStr(Activity act, int viewId)
    {
        TextView tv = (TextView)act.findViewById(viewId);
        return tv.getText().toString();
    }

    public static String getSpinnerStr(Activity act, int viewId)
    {
        Spinner s = (Spinner)act.findViewById(viewId);
        Object obj = s.getSelectedItem();
        return (obj == null) ? null : obj.toString();
    }


    public static void setListViewHeightBasedOnChildren(ListView listView)
    {
        ListAdapter listAdapter = listView.getAdapter();
        if (listAdapter == null) {
            // pre-condition
            return;
        }

        int totalHeight = 0;
        for (int i = 0; i < listAdapter.getCount(); i++) {
            View listItem = listAdapter.getView(i, null, listView);
            listItem.measure(0, 0);
            totalHeight += listItem.getMeasuredHeight();
        }

        ViewGroup.LayoutParams params = listView.getLayoutParams();
        params.height = totalHeight +
            (listView.getDividerHeight() * (listAdapter.getCount() - 1));
        listView.setLayoutParams(params);
    }


    public static ListAdapter createPasswdHistoryAdapter(PasswdHistory history,
                                                         Context context)
    {
        ArrayList<HashMap<String, Object>> histData =
            new ArrayList<HashMap<String, Object>>();
        DateFormat dateFormatter = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM, DateFormat.MEDIUM);
        for (PasswdHistory.Entry entry : history.getPasswds()) {
            HashMap<String, Object> entryData =
                new HashMap<String, Object>();
            entryData.put(PASSWD, entry.getPasswd());
            entryData.put(DATE, dateFormatter.format(entry.getDate()));
            histData.add(entryData);
        }

        ListAdapter adapter =
            new SimpleAdapter(context, histData,
                              android.R.layout.simple_list_item_2,
                              new String[] { PASSWD, DATE },
                              new int[] { android.R.id.text1,
                                          android.R.id.text2 });
        return adapter;
    }
}
