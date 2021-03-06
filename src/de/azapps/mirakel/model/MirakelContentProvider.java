/*******************************************************************************
 * Mirakel is an Android App for managing your ToDo-Lists
 * 
 * Copyright (c) 2013 Anatolij Zelenin, Georg Semmler.
 * 
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     any later version.
 * 
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 * 
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package de.azapps.mirakel.model;

import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.dmfs.provider.tasks.TaskContract;
import org.dmfs.provider.tasks.TaskContract.TaskLists;
import org.dmfs.provider.tasks.TaskContract.Tasks;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import de.azapps.mirakel.helper.DateTimeHelper;
import de.azapps.mirakel.helper.Log;
import de.azapps.mirakel.model.list.ListMirakel;
import de.azapps.mirakel.model.list.SpecialList;
import de.azapps.mirakel.model.task.Task;
import de.azapps.mirakel.sync.SyncAdapter;
import de.azapps.mirakel.sync.SyncAdapter.SYNC_STATE;

public class MirakelContentProvider extends ContentProvider {
	// public static final String PROVIDER_NAME = Mirakel.AUTHORITY_TYP;
	// public static final Uri CONTENT_URI = Uri.parse("content://" +
	// PROVIDER_NAME);
	private SQLiteDatabase database;
	private static final UriMatcher uriMatcher;
	private static final int LISTS = 5;
	private static final int LIST_ID = 6;
	private static final int TASKS = 2;
	private static final int TASK_ID = 3;

	private static final String TAG = "MirakelContentProvider";
	// TODO for what we will need this?
	private static final int INSTANCE_ID = 0;
	private static final int INSTANCES = 1;
	private static final int CATEGORIES = 6;
	private static final int CATEGORY_ID = 7;

	static {
		uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
		uriMatcher.addURI(TaskContract.AUTHORITY,
				TaskContract.TaskLists.CONTENT_URI_PATH, LISTS);

		uriMatcher.addURI(TaskContract.AUTHORITY,
				TaskContract.TaskLists.CONTENT_URI_PATH + "/#", LIST_ID);

		uriMatcher.addURI(TaskContract.AUTHORITY,
				TaskContract.Tasks.CONTENT_URI_PATH, TASKS);
		uriMatcher.addURI(TaskContract.AUTHORITY,
				TaskContract.Tasks.CONTENT_URI_PATH + "/#", TASK_ID);

		uriMatcher.addURI(TaskContract.AUTHORITY,
				TaskContract.Instances.CONTENT_URI_PATH, INSTANCES);
		uriMatcher.addURI(TaskContract.AUTHORITY,
				TaskContract.Instances.CONTENT_URI_PATH + "/#", INSTANCE_ID);

		uriMatcher.addURI(TaskContract.AUTHORITY,
				TaskContract.Categories.CONTENT_URI_PATH, CATEGORIES);
		uriMatcher.addURI(TaskContract.AUTHORITY,
				TaskContract.Categories.CONTENT_URI_PATH + "/#", CATEGORY_ID);
	}

	@Override
	public int delete(Uri uri, String selection, String[] selectionArgs) {
		if (!isCallerSyncAdapter(uri)) {
			ContentValues cv = new ContentValues();
			cv.put(SyncAdapter.SYNC_STATE, SYNC_STATE.DELETE.toInt());
			switch (uriMatcher.match(uri)) {
			case LISTS:
			case TASKS:
				return update(uri, cv, selection, selectionArgs);
			case LIST_ID:
				database.update(ListMirakel.TABLE, cv, DatabaseHelper.ID + "="
						+ getId(uri), null);
				return 1;
			case TASK_ID:
				database.update(Task.TABLE, cv, DatabaseHelper.ID + "="
						+ getId(uri), null);
				return 1;
			default:
				throw new IllegalArgumentException("Unsupported URI: " + uri);
			}
		} else {
			boolean isList = true;
			switch (uriMatcher.match(uri)) {
			case LIST_ID:
				return database.delete(ListMirakel.TABLE, DatabaseHelper.ID
						+ "=" + getId(uri), null);
			case LISTS:
				isList = true;
				break;
			case TASKS:
				isList = false;
				break;
			case TASK_ID:
				return database.delete(Task.TABLE, DatabaseHelper.ID + "="
						+ getId(uri), null);
			default:
				throw new IllegalArgumentException("Unsupported URI: " + uri);
			}
			String s = getIdsFromSelection(uri, selection, selectionArgs,
					isList);
			if (!s.equals("")) {
				return database.delete(isList ? ListMirakel.TABLE : Task.TABLE,
						DatabaseHelper.ID + " IN (" + s + ")", null);
			} else {
				throw new RuntimeException("id not found");
			}
		}
	}

	private String getIdsFromSelection(Uri uri, String selection,
			String[] selectionArgs, boolean isList) {
		Cursor c = query(uri,
				new String[] { isList ? TaskLists._ID : Tasks._ID }, selection,
				selectionArgs, null);
		String s = "";
		if (c.getCount() > 0) {
			while (!c.isAfterLast()) {
				s += (s.equals("") ? "" : ",") + c.getInt(0);
				c.moveToNext();
			}
		} else {
			throw new RuntimeException("id not found");
		}
		return s;
	}

	protected boolean isCallerSyncAdapter(Uri uri) {
		String param = uri
				.getQueryParameter(TaskContract.CALLER_IS_SYNCADAPTER);
		return param != null && !"false".equals(param);
	}

	@Override
	public String getType(Uri uri) {
		switch (uriMatcher.match(uri)) {
		case LISTS:
			return TaskContract.TaskLists.CONTENT_TYPE;
		case LIST_ID:
			return TaskContract.TaskLists.CONTENT_ITEM_TYPE;
		case TASKS:
			return TaskContract.Tasks.CONTENT_TYPE;
		case TASK_ID:
			return TaskContract.Tasks.CONTENT_ITEM_TYPE;
		case INSTANCES:
			return TaskContract.Instances.CONTENT_TYPE;
		default:
			throw new IllegalArgumentException("Unsupported URI: " + uri);
		}
	}

	@Override
	public Uri insert(Uri uri, ContentValues values) {
		ContentValues newValues = convertValues(values,
				isCallerSyncAdapter(uri));
		String table;
		switch (uriMatcher.match(uri)) {
		case LISTS:
			table = ListMirakel.TABLE;
			break;
		case TASKS:
			table = Task.TABLE;
			break;
		default:
			throw new IllegalArgumentException("Unsupported URI: " + uri);

		}
		long id;
		database.beginTransaction();
		try {
			id = database.insert(table, null, newValues);
			database.setTransactionSuccessful();
		} catch (Exception e) {
			Log.d(TAG, "cannot insert new object");
			throw new RuntimeException();
		} finally {
			database.endTransaction();
		}

		return Uri.parse(uri.toString() + "/" + id);

	}

	private ContentValues convertValues(ContentValues values,
			boolean isSyncadapter) {
		ContentValues newValues = new ContentValues();
		if (values.containsKey(Tasks.TITLE)) {
			newValues.put(DatabaseHelper.NAME, values.getAsString(Tasks.TITLE));
		}
		if (values.containsKey(TaskLists.LIST_NAME)) {
			newValues.put(DatabaseHelper.NAME, values.getAsString(Tasks.TITLE));
		}
		if (values.containsKey(Tasks.DESCRIPTION)) {
			newValues.put(Task.CONTENT, values.getAsString(Tasks.DESCRIPTION));
		}
		if (values.containsKey(Tasks.DUE)) {
			Date d = new Date(values.getAsLong(Tasks.DUE));
			GregorianCalendar due = new GregorianCalendar();
			d.setTime(values.getAsLong(Tasks.DUE));
			String db = (due == null ? null : DateTimeHelper.formatDate(due));
			newValues.put(Task.DUE, db);
		}
		if (values.containsKey(Tasks.PRIORITY)) {
			int prio = values.getAsInteger(Tasks.PRIORITY);
			if (prio > 2) {
				prio = 2;
			} else if (prio < -2) {
				prio = -2;
			}
			newValues.put(Task.PRIORITY, prio);
		}
		if (values.containsKey(Tasks.STATUS)) {
			int status = values.getAsInteger(Tasks.STATUS);
			boolean done = status == Tasks.STATUS_COMPLETED;
			newValues.put(Task.DONE, done);
		}
		if (values.containsKey(Tasks.LIST_ID)) {
			newValues.put(Task.LIST_ID, values.getAsInteger(Tasks.LIST_ID));
		}

		if (isSyncadapter) {
			if (values.containsKey(Tasks._ID)) {
				newValues
						.put(DatabaseHelper.ID, values.getAsInteger(Tasks._ID));
			}
			if (values.containsKey(TaskLists._ID)) {
				newValues.put(DatabaseHelper.ID,
						values.getAsInteger(TaskLists._ID));
			}
			if (values.containsKey(TaskLists.LIST_COLOR)) {
				newValues.put(ListMirakel.COLOR,
						values.getAsInteger(TaskLists.LIST_COLOR));
			}
			if (values.containsKey(Tasks.CREATED)) {
				Date d = new Date(values.getAsLong(Tasks.CREATED));
				GregorianCalendar due = new GregorianCalendar();
				d.setTime(values.getAsLong(Tasks.CREATED));
				String db = (due == null ? null : DateTimeHelper
						.formatDate(due));
				newValues.put(DatabaseHelper.CREATED_AT, db);
			}
			if (values.containsKey(Tasks.LAST_MODIFIED)) {
				Date d = new Date(values.getAsLong(Tasks.LAST_MODIFIED));
				GregorianCalendar due = new GregorianCalendar();
				d.setTime(values.getAsLong(Tasks.LAST_MODIFIED));
				String db = (due == null ? null : DateTimeHelper
						.formatDate(due));
				newValues.put(DatabaseHelper.UPDATED_AT, db);
			}
			if (values.containsKey(Tasks._SYNC_ID)) {
				newValues.put(Task.UUID, values.getAsString(Tasks._SYNC_ID));
			}
		}
		newValues.put(SyncAdapter.SYNC_STATE, SYNC_STATE.ADD.toInt());
		return null;
	}

	@Override
	public boolean onCreate() {
		database = new DatabaseHelper(getContext()).getWritableDatabase();
		return database == null;
	}

	private String getId(Uri uri) {
		return uri.getPathSegments().get(1);
	}

	@Override
	public Cursor query(Uri uri, String[] projection, String selection,
			String[] selectionArgs, String sortOrder) {
		SQLiteQueryBuilder sqlBuilder = new SQLiteQueryBuilder();
		boolean isSyncAdapter = isCallerSyncAdapter(uri);
		selection = insertSelectionArgs(selection, selectionArgs);
		int matcher = uriMatcher.match(uri);
		switch (matcher) {
		case LIST_ID:
		case LISTS:
			return listQuery(projection, selection, sortOrder, sqlBuilder,
					isSyncAdapter);
		case TASK_ID:
		case TASKS:
			return taskQuery(projection, selection, sortOrder, sqlBuilder,
					isSyncAdapter);

		default:
			throw new IllegalArgumentException("Unsupported URI: " + uri);
		}
	}

	private Cursor listQuery(String[] projection, String selection,
			String sortOrder, SQLiteQueryBuilder sqlBuilder,
			boolean isSyncAdapter) {
		String listQuery;
		if (selection.equals("1=1")) {
			listQuery = getListQuerySpecial();
		} else {
			listQuery = getListQuery(isSyncAdapter);
		}
		sqlBuilder.setTables("(" + listQuery + ")");

		String query = sqlBuilder.buildQuery(projection, selection, null, null,
				sortOrder, null);
		Log.d(TAG, query);
		Cursor c = database.rawQuery(query, null);
		return c;
	}

	private Cursor taskQuery(String[] projection, String selection,
			String sortOrder, SQLiteQueryBuilder sqlBuilder,
			boolean isSyncAdapter) {
		String taskQuery = getTaskQuery(isSyncAdapter);
		if (selection.contains(TaskContract.Tasks.LIST_ID)) {
			try {
				taskQuery = handleListID(projection, selection, isSyncAdapter,
						taskQuery);
			} catch (SQLWarning s) {
				return new MatrixCursor(projection);
			}
		}

		sqlBuilder.setTables("(" + taskQuery + ")");
		String query = sqlBuilder.buildQuery(projection, selection, null, null,
				sortOrder, null);
		Log.d(TAG, query);
		return database.rawQuery(query, null);
	}

	private String handleListID(String[] projection, String selection,
			boolean isSyncAdapter, String taskQuery) throws SQLWarning {
		String[] t = selection.split(TaskContract.Tasks.LIST_ID);
		boolean not;
		try {
			not = t[0].trim().substring(t[0].trim().length() - 3)
					.equalsIgnoreCase("not");
		} catch (Exception e) {
			not = false;
		}
		if (t[1].trim().charAt(0) == '=') {
			taskQuery = handleListIDEqual(isSyncAdapter, taskQuery, t, not);
		} else {
			taskQuery = handleListIDIn(isSyncAdapter, taskQuery, t, not);
		}
		return taskQuery;
	}

	private String handleListIDIn(boolean isSyncAdapter, String taskQuery,
			String[] t, boolean not) throws SQLWarning {
		if (t[1].trim().substring(0, 2).equalsIgnoreCase("in")) {
			t[1] = t[1].trim().substring(3).trim();
			int counter = 1;
			String buffer = "";
			List<Integer> idList = new ArrayList<Integer>();
			while ((t[1].charAt(counter) >= '0' && t[1].charAt(counter) <= '9')
					|| t[1].charAt(counter) == ','
					|| t[1].charAt(counter) == ' '
					|| t[1].charAt(counter) == '-') {
				if (t[1].charAt(counter) == ',') {
					try {
						idList.add(Integer.parseInt(buffer));
						buffer = "";
					} catch (NumberFormatException e) {
						Log.e(TAG, "cannot parse list id");
						throw new SQLWarning();
					}
				} else if ((t[1].charAt(counter) >= '0' && t[1].charAt(counter) <= '9')
						|| t[1].charAt(counter) == '-') {
					buffer += t[1].charAt(counter);
				}
				++counter;
			}
			try {
				idList.add(Integer.parseInt(buffer));
			} catch (NumberFormatException e) {
				Log.e(TAG, "cannot parse list id");
				throw new SQLWarning();
			}
			if (idList.size() == 0) {
				Log.e(TAG, "inavlid SQL");
				throw new SQLWarning();
			}
			List<String> wheres = new ArrayList<String>();
			List<Integer> ordonaryIds = new ArrayList<Integer>();
			for (int id : idList) {
				if (id < 0) {
					SpecialList s = SpecialList.getSpecialList(-1 * id);
					if (s != null) {
						wheres.add(s.getWhereQuery(true));
					} else {
						Log.e(TAG, "no matching list found");
						throw new SQLWarning();

					}
				} else {
					ordonaryIds.add(id);
				}
			}
			taskQuery = getTaskQuery(true, not ? 0 : idList.get(0),
					isSyncAdapter) + " WHERE " + (not ? " NOT (" : "");
			for (int i = 0; i < wheres.size(); i++) {
				taskQuery += (i != 0 ? " AND " : " ") + wheres.get(i);
			}
			if (ordonaryIds.size() > 0) {
				if (wheres.size() > 0) {
					taskQuery += " OR ";
				}
				taskQuery += Task.LIST_ID + " IN (";
				for (int i = 0; i < ordonaryIds.size(); i++) {
					taskQuery += (i != 0 ? "," : "") + ordonaryIds.get(i);
				}
				taskQuery += ")";
			}
			taskQuery += (not ? ")" : "");

		}
		return taskQuery;
	}

	private String handleListIDEqual(boolean isSyncAdapter, String taskQuery,
			String[] t, boolean not) throws SQLWarning {
		t[1] = t[1].trim().substring(1);
		int list_id = 0;
		try {
			boolean negative = t[1].trim().charAt(0) == '-';
			Matcher matcher = Pattern.compile("\\d+").matcher(t[1]);
			matcher.find();
			list_id = (negative ? -1 : 1) * Integer.valueOf(matcher.group());
		} catch (Exception e) {
			Log.e(TAG, "cannot parse list_id");
			throw new SQLWarning();
		}
		if (list_id < 0) {// is special list...
			SpecialList s = SpecialList.getSpecialList(-1 * list_id);
			if (s != null) {
				taskQuery = getTaskQuery(true, not ? 0 : list_id, isSyncAdapter)
						+ " WHERE "
						+ (not ? "NOT ( " : "")
						+ s.getWhereQuery(true) + (not ? " )" : "");
			} else {
				Log.e(TAG, "no matching list found");
				throw new SQLWarning();
			}
		}
		return taskQuery;
	}

	private String insertSelectionArgs(String selection, String[] selectionArgs) {
		if (selectionArgs != null) {
			for (int i = 0; i < selectionArgs.length; i++) {
				selection = selection.replace("?", selectionArgs[i]);
			}
		}
		return selection;
	}

	private String getTaskQuery(boolean isSyncAdapter) {
		return getTaskQuery(false, 0, isSyncAdapter);
	}

	private String getTaskQuery(boolean isSpecial, int list_id,
			boolean isSyncadapter) {
		String query = "SELECT ";
		query += addSegment(DatabaseHelper.NAME, TaskContract.Tasks.TITLE,
				false);
		query += addSegment(Task.CONTENT, TaskContract.Tasks.DESCRIPTION, true);
		query += addSegment(Task.PRIORITY, TaskContract.Tasks.PRIORITY, true);
		query += addSegment("strftime('%s'," + Task.DUE + ")*1000",
				TaskContract.Tasks.DUE, true);
		query += addSegment(Task.DONE, TaskContract.Tasks.STATUS, true);
		if (isSpecial) {
			query += addSegment("CASE " + Task.LIST_ID + " WHEN 1 THEN "
					+ list_id + " ELSE " + list_id + " END",
					TaskContract.Tasks.LIST_ID, true);
		} else {
			query += addSegment(Task.LIST_ID, TaskContract.Tasks.LIST_ID, true);
		}
		if (isSyncadapter) {
			query += addSegment("CASE " + SyncAdapter.SYNC_STATE + " WHEN "
					+ SYNC_STATE.NEED_SYNC + " THEN TRUE ELSE FALSE",
					TaskContract.Tasks._DIRTY, true);
			query += addSegment(DatabaseHelper.ID, Tasks._ID, true);
			query += addSegment("CASE " + SyncAdapter.SYNC_STATE + " WHEN "
					+ SYNC_STATE.DELETE + " THEN TRUE ELSE FALSE",
					TaskContract.Tasks._DELETED, true);
			query += addSegment("CASE " + SyncAdapter.SYNC_STATE + " WHEN "
					+ SYNC_STATE.ADD + " THEN TRUE ELSE FALSE",
					TaskContract.Tasks.IS_NEW, true);
			query += addSegment(Task.UUID, Tasks._SYNC_ID, true);
		}
		query += addSegment("strftime('%s'," + DatabaseHelper.UPDATED_AT
				+ ")*1000", Tasks.LAST_MODIFIED, true);
		query += addSegment("strftime('%s'," + DatabaseHelper.CREATED_AT
				+ ")*1000", Tasks.CREATED, true);
		query += " FROM " + Task.TABLE;
		Log.d(TAG, query);
		return query;
	}

	private String getListQuerySpecial() {
		String query = getListQuery(false);
		query += " UNION ";
		query += getListQueryBase(false);
		query += addSegment(DatabaseHelper.ID + "*-1", TaskLists._ID, true);
		query += " FROM " + SpecialList.TABLE;
		return query;
	}

	private String getListQuery(boolean isSyncAdapter) {
		String query = getListQueryBase(isSyncAdapter);
		query += addSegment(DatabaseHelper.ID, TaskLists._ID, true);
		query += " FROM " + ListMirakel.TABLE;
		Log.d(TAG, query);
		return query;
	}

	private String getListQueryBase(boolean isSyncAdapter) {
		String query = "SELECT ";
		query += addSegment(DatabaseHelper.NAME, TaskLists.LIST_NAME, false);
		query += addSegment(ListMirakel.COLOR, TaskLists.LIST_COLOR, true);
		if (isSyncAdapter) {
			query += addSegment("CASE " + SyncAdapter.SYNC_STATE + " WHEN "
					+ SYNC_STATE.NEED_SYNC + " THEN TRUE ELSE FALSE",
					TaskLists._DIRTY, true);
			query += addSegment(DatabaseHelper.ID, Tasks._ID, true);
			// query += addSegment("CASE " + SyncAdapter.SYNC_STATE + " WHEN "
			// + SYNC_STATE.DELETE + " THEN TRUE ELSE FALSE",
			// TaskLists._DELETED, true);
			// query += addSegment("CASE " + SyncAdapter.SYNC_STATE + " WHEN "
			// + SYNC_STATE.ADD + " THEN TRUE ELSE FALSE",
			// TaskLists.IS_NEW, true);
			query += addSegment(DatabaseHelper.ID, TaskLists._SYNC_ID, true);
		}
		return query;
	}

	private String addSegment(String ownName, String remoteName, boolean comma) {
		return (comma ? " , " : " ") + ownName + " as " + remoteName;
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection,
			String[] selectionArgs) {
		ContentValues newValues = convertValues(values,
				isCallerSyncAdapter(uri));
		boolean isList;
		switch (uriMatcher.match(uri)) {
		case TASKS:
			isList = false;
			break;
		case TASK_ID:
			return database.update(Task.TABLE, newValues, DatabaseHelper.ID
					+ "=" + getId(uri), null);
		case LIST_ID:
			return database.update(ListMirakel.TABLE, newValues,
					DatabaseHelper.ID + "=" + getId(uri), null);
		case LISTS:
			isList = true;
		default:
			throw new IllegalArgumentException("Unsupported URI: " + uri);
		}
		String s = getIdsFromSelection(uri, selection, selectionArgs, isList);
		if (!s.equals("")) {
			return database.update(isList ? ListMirakel.TABLE : Task.TABLE,
					newValues, DatabaseHelper.ID + " IN(" + s + ")", null);
		} else {
			throw new RuntimeException("id not found");
		}
	}

}
