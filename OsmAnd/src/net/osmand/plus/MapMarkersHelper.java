package net.osmand.plus;

import android.content.Context;
import android.os.AsyncTask;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;

import net.osmand.AndroidUtils;
import net.osmand.IndexConstants;
import net.osmand.data.FavouritePoint;
import net.osmand.data.LatLon;
import net.osmand.data.LocationPoint;
import net.osmand.data.PointDescription;
import net.osmand.plus.FavouritesDbHelper.FavoriteGroup;
import net.osmand.plus.GPXUtilities.GPXFile;
import net.osmand.plus.GPXUtilities.WptPt;
import net.osmand.plus.GeocodingLookupService.AddressLookupRequest;
import net.osmand.plus.GpxSelectionHelper.SelectedGpxFile;
import net.osmand.plus.mapmarkers.MapMarkersDbHelper;
import net.osmand.plus.mapmarkers.MarkersPlanRouteContext;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static net.osmand.data.PointDescription.POINT_TYPE_MAP_MARKER;

public class MapMarkersHelper {

	public static final int MAP_MARKERS_COLORS_COUNT = 7;

	public static final int BY_NAME = 0;
	public static final int BY_DISTANCE_DESC = 1;
	public static final int BY_DISTANCE_ASC = 2;
	public static final int BY_DATE_ADDED_DESC = 3;
	public static final int BY_DATE_ADDED_ASC = 4;

	@Retention(RetentionPolicy.SOURCE)
	@IntDef({BY_NAME, BY_DISTANCE_DESC, BY_DISTANCE_ASC, BY_DATE_ADDED_DESC, BY_DATE_ADDED_ASC})
	public @interface MapMarkersSortByDef {
	}

	private OsmandApplication ctx;
	private OsmandSettings settings;
	private MapMarkersDbHelper markersDbHelper;

	private ExecutorService executorService = Executors.newSingleThreadExecutor();

	private List<MapMarker> mapMarkers = new ArrayList<>();
	private List<MapMarker> mapMarkersHistory = new ArrayList<>();
	private List<MapMarkersGroup> mapMarkersGroups = new ArrayList<>();

	private List<MapMarkerChangedListener> listeners = new ArrayList<>();

	private boolean startFromMyLocation;

	private MarkersPlanRouteContext planRouteContext;


	public List<MapMarker> getMapMarkers() {
		return mapMarkers;
	}

	public List<MapMarker> getMapMarkersHistory() {
		return mapMarkersHistory;
	}

	public List<MapMarkersGroup> getMapMarkersGroups() {
		return mapMarkersGroups;
	}

	public boolean isStartFromMyLocation() {
		return startFromMyLocation;
	}

	public void setStartFromMyLocation(boolean startFromMyLocation) {
		this.startFromMyLocation = startFromMyLocation;
		settings.ROUTE_MAP_MARKERS_START_MY_LOC.set(startFromMyLocation);
	}

	public MarkersPlanRouteContext getPlanRouteContext() {
		return planRouteContext;
	}

	public MapMarkersHelper(OsmandApplication ctx) {
		this.ctx = ctx;
		settings = ctx.getSettings();
		markersDbHelper = ctx.getMapMarkersDbHelper();
		planRouteContext = new MarkersPlanRouteContext(ctx);
		startFromMyLocation = settings.ROUTE_MAP_MARKERS_START_MY_LOC.get();
		markersDbHelper.removeDisabledGroups();
		loadMarkers();
		createMapMarkersGroups();
	}

	private void loadMarkers() {
		mapMarkers = new ArrayList<>();
		mapMarkersHistory = new ArrayList<>();

		addToMapMarkersList(markersDbHelper.getActiveMarkers());
		reorderActiveMarkersIfNeeded();

		List<MapMarker> markersHistory = markersDbHelper.getMarkersHistory();
		sortMarkers(markersHistory, true, BY_DATE_ADDED_DESC);
		addToMapMarkersHistoryList(markersHistory);

		if (!ctx.isApplicationInitializing()) {
			lookupAddressAll();
		}
	}

	public void lookupAddressAll() {
		for (MapMarker mapMarker : mapMarkers) {
			lookupAddress(mapMarker);
		}
		for (MapMarker mapMarker : mapMarkersHistory) {
			lookupAddress(mapMarker);
		}
	}

	private void lookupAddress(final MapMarker mapMarker) {
		if (mapMarker != null && mapMarker.pointDescription.isSearchingAddress(ctx)) {
			cancelPointAddressRequests(mapMarker.point);
			AddressLookupRequest lookupRequest = new AddressLookupRequest(mapMarker.point,
					new GeocodingLookupService.OnAddressLookupResult() {
						@Override
						public void geocodingDone(String address) {
							if (Algorithms.isEmpty(address)) {
								mapMarker.pointDescription.setName(PointDescription.getAddressNotFoundStr(ctx));
							} else {
								mapMarker.pointDescription.setName(address);
							}
							markersDbHelper.updateMarker(mapMarker);
							refreshMarker(mapMarker);
						}
					}, null);
			ctx.getGeocodingLookupService().lookupAddress(lookupRequest);
		}
	}

	private void cancelAddressRequests() {
		List<MapMarker> markers = new ArrayList<>(mapMarkers);
		for (MapMarker m : markers) {
			cancelPointAddressRequests(m.point);
		}
		markers = new ArrayList<>(mapMarkersHistory);
		for (MapMarker m : markers) {
			cancelPointAddressRequests(m.point);
		}
	}

	private void cancelPointAddressRequests(LatLon latLon) {
		if (latLon != null) {
			ctx.getGeocodingLookupService().cancel(latLon);
		}
	}

	public void reorderActiveMarkersIfNeeded() {
		if (!mapMarkers.isEmpty()) {
			if (mapMarkers.size() > 1) {
				for (int i = 0; i < mapMarkers.size() - 1; i++) {
					MapMarker first = mapMarkers.get(i);
					MapMarker second = mapMarkers.get(i + 1);
					if (!first.nextKey.equals(second.id)) {
						markersDbHelper.changeActiveMarkerPosition(first, second);
						first.nextKey = second.id;
					}
				}
			}

			MapMarker tail = mapMarkers.get(mapMarkers.size() - 1);
			if (!tail.nextKey.equals(MapMarkersDbHelper.TAIL_NEXT_VALUE)) {
				markersDbHelper.changeActiveMarkerPosition(tail, null);
			}
		}
	}

	public void sortMarkers(final @MapMarkersSortByDef int sortByMode, LatLon location) {
		sortMarkers(getMapMarkers(), false, sortByMode, location);
		reorderActiveMarkersIfNeeded();
	}

	private void sortMarkers(List<MapMarker> markers, final boolean visited, final @MapMarkersSortByDef int sortByMode) {
		sortMarkers(markers, visited, sortByMode, null);
	}

	private void sortMarkers(List<MapMarker> markers,
							 final boolean visited,
							 final @MapMarkersSortByDef int sortByMode,
							 @Nullable final LatLon location) {
		Collections.sort(markers, new Comparator<MapMarker>() {
			@Override
			public int compare(MapMarker mapMarker1, MapMarker mapMarker2) {
				if (sortByMode == BY_DATE_ADDED_DESC || sortByMode == BY_DATE_ADDED_ASC) {
					long t1 = visited ? mapMarker1.visitedDate : mapMarker1.creationDate;
					long t2 = visited ? mapMarker2.visitedDate : mapMarker2.creationDate;
					if (t1 > t2) {
						return sortByMode == BY_DATE_ADDED_DESC ? -1 : 1;
					} else if (t1 == t2) {
						return 0;
					} else {
						return sortByMode == BY_DATE_ADDED_DESC ? 1 : -1;
					}
				} else if (location != null && (sortByMode == BY_DISTANCE_DESC || sortByMode == BY_DISTANCE_ASC)) {
					int d1 = (int) MapUtils.getDistance(location, mapMarker1.getLatitude(), mapMarker1.getLongitude());
					int d2 = (int) MapUtils.getDistance(location, mapMarker2.getLatitude(), mapMarker2.getLongitude());
					if (d1 > d2) {
						return sortByMode == BY_DISTANCE_DESC ? -1 : 1;
					} else if (d1 == d2) {
						return 0;
					} else {
						return sortByMode == BY_DISTANCE_DESC ? 1 : -1;
					}
				} else {
					String n1 = mapMarker1.getName(ctx);
					String n2 = mapMarker2.getName(ctx);
					return n1.compareToIgnoreCase(n2);
				}
			}
		});
	}

	public boolean isGroupSynced(String id) {
		return markersDbHelper.getGroup(id) != null;
	}

	public boolean isGroupDisabled(String id) {
		return markersDbHelper.isGroupDisabled(id);
	}

	public void syncAllGroupsAsync() {
		List<MarkersSyncGroup> groups = markersDbHelper.getAllGroups();
		for (MarkersSyncGroup gr : groups) {
			syncGroupAsync(gr);
		}
	}

	public void syncGroupAsync(@NonNull MarkersSyncGroup group) {
		syncGroupAsync(group, true, null);
	}

	public void syncGroupAsync(@NonNull MarkersSyncGroup group, @Nullable OnGroupSyncedListener listener) {
		syncGroupAsync(group, true, listener);
	}

	public void syncGroupAsync(@NonNull final MarkersSyncGroup group,
							   final boolean enabled,
							   @Nullable final OnGroupSyncedListener listener) {
		ctx.runInUIThread(new Runnable() {
			@Override
			public void run() {
				SyncGroupTask syncGroupTask = new SyncGroupTask(group, enabled, listener);
				syncGroupTask.executeOnExecutor(executorService);
			}
		});
	}

	public boolean isSynced(SelectedGpxFile gpxFile) {
		List<WptPt> gpxPoints = gpxFile.getGpxFile().getPoints();
		for (WptPt wptPt : gpxPoints) {
			if (getMapMarker(wptPt) != null) {
				return true;
			}
		}
		return false;
	}

	public boolean isSynced(FavouritePoint favouritePoint) {
		return getMapMarker(favouritePoint) != null;
	}

	@Nullable
	public MapMarker getMapMarker(WptPt wptPt) {
		for (MapMarker marker : mapMarkers) {
			if (marker.wptPt == wptPt) {
				return marker;
			}
		}
		return null;
	}

	@Nullable
	public MapMarker getMapMarker(FavouritePoint favouritePoint) {
		for (MapMarker marker : mapMarkers) {
			if (marker.favouritePoint == favouritePoint) {
				return marker;
			}
		}
		return null;
	}

	@Nullable
	public MapMarker getMapMarker(@NonNull LatLon latLon) {
		for (MapMarker marker : mapMarkers) {
			if (marker.point != null && marker.point.equals(latLon)) {
				return marker;
			}
		}
		return null;
	}

	@Nullable
	public MapMarker getMapMarker(@NonNull String mapObjectName, @NonNull LatLon latLon) {
		for (MapMarker marker : mapMarkers) {
			if (marker.mapObjectName != null
					&& marker.point != null
					&& marker.mapObjectName.equals(mapObjectName)
					&& MapUtils.getDistance(latLon, marker.point) < 15) {
				return marker;
			}
		}
		return null;
	}

	private void addNewMarkerIfNeeded(@NonNull MarkersSyncGroup group,
									  @NonNull List<MapMarker> markers,
									  @NonNull LatLon latLon,
									  @NonNull String name,
									  boolean enabled,
									  @Nullable FavouritePoint favouritePoint,
									  @Nullable WptPt wptPt) {
		boolean exists = false;

		for (MapMarker marker : markers) {
			if (marker.id.equals(group.getId() + name)) {
				exists = true;
				for (MapMarker m : mapMarkers) {
					if (m.id.equals(marker.id)) {
						m.favouritePoint = favouritePoint;
						m.wptPt = wptPt;
						if (!marker.history && !marker.point.equals(latLon)) {
							m.point = latLon;
							updateMapMarker(m, true);
						}
						break;
					}
				}
				markers.remove(marker);
				break;
			}
		}

		if (!exists) {
			addMarkers(Collections.singletonList(latLon),
					Collections.singletonList(new PointDescription(POINT_TYPE_MAP_MARKER, name)),
					group,
					enabled,
					Collections.singletonList(favouritePoint),
					Collections.singletonList(wptPt),
					null);
		}
	}

	private void removeOldMarkersIfNeeded(List<MapMarker> markers) {
		if (!markers.isEmpty()) {
			boolean needRefresh = false;
			for (MapMarker marker : markers) {
				if (!marker.history) {
					markersDbHelper.removeMarker(marker, false);
					removeFromMapMarkersList(marker);
					removeMarkerFromGroup(marker);
					needRefresh = true;
				}
			}
			if (needRefresh) {
				reorderActiveMarkersIfNeeded();
				refresh();
			}
		}
	}

	public void moveMapMarkerToHistory(MapMarker marker) {
		if (marker != null) {
			cancelPointAddressRequests(marker.point);
			markersDbHelper.moveMarkerToHistory(marker);
			removeFromMapMarkersList(marker);
			marker.history = true;
			marker.nextKey = MapMarkersDbHelper.HISTORY_NEXT_VALUE;
			addToMapMarkersHistoryList(marker);
			reorderActiveMarkersIfNeeded();
			sortMarkers(mapMarkersHistory, true, BY_DATE_ADDED_DESC);
			refresh();
		}
	}

	public void addMarkers(List<MapMarker> markers) {
		if (markers != null) {
			markersDbHelper.addMarkers(markers);
			addToMapMarkersList(markers);
			reorderActiveMarkersIfNeeded();
			addMarkersToGroups(markers, true);
			refresh();
		}
	}

	public void addMarker(MapMarker marker) {
		if (marker != null) {
			markersDbHelper.addMarker(marker);
			if (marker.history) {
				addToMapMarkersHistoryList(marker);
				sortMarkers(mapMarkersHistory, true, BY_DATE_ADDED_DESC);
			} else {
				addToMapMarkersList(marker);
				reorderActiveMarkersIfNeeded();
			}
			addMarkerToGroup(marker);
			refresh();
		}
	}

	public void restoreMarkerFromHistory(MapMarker marker, int position) {
		if (marker != null) {
			markersDbHelper.restoreMapMarkerFromHistory(marker);
			removeFromMapMarkersHistoryList(marker);
			marker.history = false;
			addToMapMarkersList(position, marker);
			reorderActiveMarkersIfNeeded();
			sortMarkers(mapMarkersHistory, true, BY_DATE_ADDED_DESC);
			refresh();
		}
	}

	public void restoreMarkersFromHistory(List<MapMarker> markers) {
		if (markers != null) {
			for (MapMarker marker : markers) {
				markersDbHelper.restoreMapMarkerFromHistory(marker);
				marker.history = false;
			}
			removeFromMapMarkersHistoryList(markers);
			addToMapMarkersList(markers);
			reorderActiveMarkersIfNeeded();
			sortMarkers(mapMarkersHistory, true, BY_DATE_ADDED_DESC);
			updateGroups();
			refresh();
		}
	}

	public void removeMarker(MapMarker marker) {
		if (marker != null) {
			boolean history = marker.history;
			markersDbHelper.removeMarker(marker, history);
			if (history) {
				removeFromMapMarkersHistoryList(marker);
			} else {
				removeFromMapMarkersList(marker);
			}
			removeMarkerFromGroup(marker);
			refresh();
		}
	}

	@Nullable
	public MapMarker getFirstMapMarker() {
		return mapMarkers.size() > 0 ? mapMarkers.get(0) : null;
	}

	public void deselectAllActiveMarkers() {
		for (MapMarker m : mapMarkers) {
			if (m.selected) {
				m.selected = false;
				markersDbHelper.updateMarker(m);
			}
		}
	}

	public void selectAllActiveMarkers() {
		for (MapMarker m : mapMarkers) {
			if (!m.selected) {
				m.selected = true;
				markersDbHelper.updateMarker(m);
			}
		}
	}

	public List<MapMarker> getSelectedMarkers() {
		List<MapMarker> list = new ArrayList<>();
		for (MapMarker m : mapMarkers) {
			if (m.selected) {
				list.add(m);
			}
		}
		return list;
	}

	public int getSelectedMarkersCount() {
		int res = 0;
		for (MapMarker m : mapMarkers) {
			if (m.selected) {
				res++;
			}
		}
		return res;
	}

	public void addSelectedMarkersToTop(@NonNull List<MapMarker> markers) {
		List<MapMarker> markersToRemove = new ArrayList<>();
		for (MapMarker m : mapMarkers) {
			if (m.selected) {
				if (!markers.contains(m)) {
					return;
				}
				markersToRemove.add(m);
			}
		}
		if (markersToRemove.size() != markers.size()) {
			return;
		}

		removeFromMapMarkersList(markersToRemove);
		addToMapMarkersList(0, markers);
		reorderActiveMarkersIfNeeded();
	}

	public List<LatLon> getSelectedMarkersLatLon() {
		List<LatLon> list = new ArrayList<>();
		for (MapMarker m : this.mapMarkers) {
			if (m.selected) {
				list.add(m.point);
			}
		}
		return list;
	}

	public void reverseActiveMarkersOrder() {
		cancelAddressRequests();
		Collections.reverse(mapMarkers);
		reorderActiveMarkersIfNeeded();
	}

	public void moveAllActiveMarkersToHistory() {
		cancelAddressRequests();
		long timestamp = System.currentTimeMillis();
		markersDbHelper.moveAllActiveMarkersToHistory(timestamp);
		for (MapMarker marker : mapMarkers) {
			marker.visitedDate = timestamp;
			marker.history = true;
			marker.nextKey = MapMarkersDbHelper.HISTORY_NEXT_VALUE;
		}
		addToMapMarkersHistoryList(mapMarkers);
		mapMarkers = new ArrayList<>();
		sortMarkers(mapMarkersHistory, true, BY_DATE_ADDED_DESC);
		updateGroups();
		refresh();
	}

	public void addMarkersSyncGroup(MarkersSyncGroup group) {
		if (group != null) {
			if (markersDbHelper.getGroup(group.getId()) == null) {
				markersDbHelper.addGroup(group.getId(), group.getName(), group.getType(), group.getWptCategoriesString());
			} else {
				markersDbHelper.updateSyncGroupCategories(group.getId(), group.getWptCategoriesString());
			}
		}
	}

	public void removeMarkersSyncGroup(String id) {
		if (id != null) {
			markersDbHelper.removeMarkersSyncGroup(id);
			removeActiveMarkersFromSyncGroup(id);
			MapMarkersGroup group = getMapMarkerGroupByKey(id);
			if (group != null) {
				removeFromGroupsList(group);
			}
		}
	}

	public void updateGroupDisabled(@NonNull MapMarkersGroup group, boolean disabled) {
		String id = group.getGroupKey();
		if (id != null) {
			markersDbHelper.updateSyncGroupDisabled(id, disabled);
			updateSyncGroupDisabled(group, disabled);
		}
	}

	private void updateSyncGroupDisabled(@NonNull MapMarkersGroup group, boolean disabled) {
		List<MapMarker> groupMarkers = new ArrayList<>(group.getMarkers());
		for (MapMarker marker : groupMarkers) {
			if (marker.history) {
				if (disabled) {
					removeFromMapMarkersHistoryList(marker);
				} else {
					addToMapMarkersHistoryList(marker);
				}
			} else {
				if (disabled) {
					removeFromMapMarkersList(marker);
				} else {
					addToMapMarkersList(marker);
				}
			}
		}
		reorderActiveMarkersIfNeeded();
		sortMarkers(mapMarkersHistory, true, BY_DATE_ADDED_DESC);
		refresh();
	}

	private void removeActiveMarkersFromSyncGroup(String syncGroupId) {
		if (syncGroupId != null) {
			markersDbHelper.removeActiveMarkersFromSyncGroup(syncGroupId);
			List<MapMarker> newList = new ArrayList<>();
			List<MapMarker> copyList = new ArrayList<>(mapMarkers);
			for (MapMarker marker : copyList) {
				if (marker.groupKey == null || !marker.groupKey.equals(syncGroupId)) {
					newList.add(marker);
				}
			}
			mapMarkers = newList;
			reorderActiveMarkersIfNeeded();
			refresh();
		}
	}

	public void addMapMarker(@NonNull LatLon point, @Nullable PointDescription historyName) {
		addMapMarkers(Collections.singletonList(point), Collections.singletonList(historyName), null);
	}

	public void addMapMarker(@NonNull LatLon point,
							 @Nullable PointDescription historyName,
							 @Nullable String mapObjectName) {
		addMarkers(Collections.singletonList(point),
				Collections.singletonList(historyName),
				null,
				true,
				null,
				null,
				Collections.singletonList(mapObjectName));
	}

	public void addMapMarkers(@NonNull List<LatLon> points,
							  @NonNull List<PointDescription> historyNames,
							  @Nullable MarkersSyncGroup group) {
		addMarkers(points, historyNames, group, true, null, null, null);
	}

	private void addMarkers(@NonNull List<LatLon> points,
							@NonNull List<PointDescription> historyNames,
							@Nullable MarkersSyncGroup group,
							boolean enabled,
							@Nullable List<FavouritePoint> favouritePoints,
							@Nullable List<WptPt> wptPts,
							@Nullable List<String> mapObjNames) {
		if (points.size() > 0) {
			int colorIndex = -1;
			List<MapMarker> addedMarkers = new ArrayList<>();
			for (int i = 0; i < points.size(); i++) {
				LatLon point = points.get(i);
				PointDescription historyName = historyNames.get(i);
				FavouritePoint favouritePoint = favouritePoints == null ? null : favouritePoints.get(i);
				WptPt wptPt = wptPts == null ? null : wptPts.get(i);
				String mapObjName = mapObjNames == null ? null : mapObjNames.get(i);
				final PointDescription pointDescription;
				if (historyName == null) {
					pointDescription = new PointDescription(PointDescription.POINT_TYPE_LOCATION, "");
				} else {
					pointDescription = historyName;
				}
				if (pointDescription.isLocation() && Algorithms.isEmpty(pointDescription.getName())) {
					pointDescription.setName(PointDescription.getSearchAddressStr(ctx));
				}
				if (colorIndex == -1) {
					if (mapMarkers.size() > 0) {
						colorIndex = (mapMarkers.get(0).colorIndex + 1) % MAP_MARKERS_COLORS_COUNT;
					} else {
						colorIndex = 0;
					}
				} else {
					colorIndex = (colorIndex + 1) % MAP_MARKERS_COLORS_COUNT;
				}

				MapMarker marker = new MapMarker(point, pointDescription, colorIndex, false, 0);
				if (group != null) {
					marker.id = group.getId() + marker.getName(ctx);
					if (markersDbHelper.getMarker(marker.id) != null) {
						continue;
					}
					marker.groupName = group.getName();
					marker.groupKey = group.getId();
				}
				marker.history = false;
				marker.nextKey = MapMarkersDbHelper.TAIL_NEXT_VALUE;
				marker.favouritePoint = favouritePoint;
				marker.wptPt = wptPt;
				marker.mapObjectName = mapObjName;
				markersDbHelper.addMarker(marker);
				if (enabled) {
					addToMapMarkersList(0, marker);
				}
				addedMarkers.add(marker);
				reorderActiveMarkersIfNeeded();
				lookupAddress(marker);
			}
			addMarkersToGroups(addedMarkers, enabled);
		}
	}

	public void updateMapMarker(MapMarker marker, boolean refresh) {
		if (marker != null) {
			markersDbHelper.updateMarker(marker);
			if (refresh) {
				refresh();
			}
		}
	}

	public void moveMarkerToTop(MapMarker marker) {
		int i = mapMarkers.indexOf(marker);
		if (i != -1 && mapMarkers.size() > 1) {
			removeFromMapMarkersList(marker);
			addToMapMarkersList(0, marker);
			reorderActiveMarkersIfNeeded();
			refresh();
		}
	}

	public void moveMapMarker(MapMarker marker, LatLon latLon) {
		if (marker != null) {
			LatLon point = new LatLon(latLon.getLatitude(), latLon.getLongitude());
			int index = mapMarkers.indexOf(marker);
			if (index != -1) {
				mapMarkers.get(index).point = point;
			}
			marker.point = point;
			markersDbHelper.updateMarker(marker);
			reorderActiveMarkersIfNeeded();
			refresh();
			lookupAddress(marker);
		}
	}

	public void addListener(MapMarkerChangedListener l) {
		if (!listeners.contains(l)) {
			listeners.add(l);
		}
	}

	public void removeListener(MapMarkerChangedListener l) {
		listeners.remove(l);
	}

	private void refreshMarker(final MapMarker marker) {
		ctx.runInUIThread(new Runnable() {
			@Override
			public void run() {
				for (MapMarkerChangedListener l : listeners) {
					l.onMapMarkerChanged(marker);
				}
			}
		});
	}

	private void refresh() {
		ctx.runInUIThread(new Runnable() {
			@Override
			public void run() {
				for (MapMarkerChangedListener l : listeners) {
					l.onMapMarkersChanged();
				}
			}
		});
	}

	public String generateGpx(String fileName) {
		final File dir = ctx.getAppPath(IndexConstants.GPX_INDEX_DIR + "/map markers");
		if (!dir.exists()) {
			dir.mkdirs();
		}
		File fout = new File(dir, fileName + ".gpx");
		int ind = 1;
		while (fout.exists()) {
			fout = new File(dir, fileName + "_" + (++ind) + ".gpx");
		}
		GPXFile file = new GPXFile();
		for (MapMarker marker : mapMarkers) {
			WptPt wpt = new WptPt();
			wpt.lat = marker.getLatitude();
			wpt.lon = marker.getLongitude();
			wpt.setColor(ctx.getResources().getColor(MapMarker.getColorId(marker.colorIndex)));
			wpt.name = marker.getOnlyName();
			file.addPoint(wpt);
		}
		GPXUtilities.writeGpxFile(fout, file, ctx);
		return fout.getAbsolutePath();
	}

	private void removeActiveMarkersFromGroup(String groupId) {
		MapMarkersGroup group = getMapMarkerGroupByKey(groupId);
		if (group != null) {
			List<MapMarker> markers = group.getMarkers();
			List<MapMarker> historyMarkers = new ArrayList<>();
			for (MapMarker marker : markers) {
				if (marker.history) {
					historyMarkers.add(marker);
				}
			}
			group.setMarkers(historyMarkers);
			updateGroup(group);
		}
	}

	public void updateGroups() {
		for (MapMarkersGroup group : mapMarkersGroups) {
			updateGroup(group);
		}
	}

	public void updateGroup(MapMarkersGroup mapMarkersGroup) {
		if (mapMarkersGroup.getMarkers().size() == 0) {
			removeFromGroupsList(mapMarkersGroup);
			return;
		}
		int historyMarkersCount = mapMarkersGroup.getHistoryMarkers().size();
		ShowHideHistoryButton showHideHistoryButton = mapMarkersGroup.getShowHideHistoryButton();
		if (showHideHistoryButton != null) {
			if (historyMarkersCount == 0) {
				mapMarkersGroup.setShowHideHistoryButton(null);
			}
		} else if (historyMarkersCount > 0) {
			showHideHistoryButton = new ShowHideHistoryButton();
			showHideHistoryButton.setShowHistory(false);
			mapMarkersGroup.setShowHideHistoryButton(showHideHistoryButton);
		}
	}

	private void addMarkersToGroups(@NonNull List<MapMarker> markers, boolean enabled) {
		List<MapMarkersGroup> groups = new ArrayList<>();
		for (MapMarker marker : markers) {
			MapMarkersGroup group = addMarkerToGroup(marker);
			if (group != null && !groups.contains(group)) {
				groups.add(group);
			}
		}
		if (!enabled) {
			for (MapMarkersGroup mapMarkersGroup : groups) {
				mapMarkersGroup.setDisabled(true);
				updateGroupDisabled(mapMarkersGroup, true);
			}
		}
	}

	@Nullable
	private MapMarkersGroup addMarkerToGroup(MapMarker marker) {
		if (marker != null) {
			MapMarkersGroup mapMarkersGroup = getMapMarkerGroupByName(marker.groupName);
			if (mapMarkersGroup != null) {
				mapMarkersGroup.getMarkers().add(marker);
				updateGroup(mapMarkersGroup);
				if (mapMarkersGroup.getName() == null) {
					sortMarkers(mapMarkersGroup.getMarkers(), false, BY_DATE_ADDED_DESC);
				}
			} else {
				mapMarkersGroup = createMapMarkerGroup(marker);
				mapMarkersGroup.getMarkers().add(marker);
				createHeaderAndHistoryButtonInGroup(mapMarkersGroup);
			}
			return mapMarkersGroup;
		}
		return null;
	}

	private MapMarkersGroup createMapMarkerGroup(@NonNull MapMarker marker) {
		MapMarkersGroup group = new MapMarkersGroup();
		if (marker.groupName != null) {
			group.setName(marker.groupName);
			group.setGroupKey(marker.groupKey);
			MarkersSyncGroup syncGroup = markersDbHelper.getGroup(marker.groupKey);
			if (syncGroup != null) {
				group.setType(syncGroup.getType());
			} else {
				group.setType(MarkersSyncGroup.FAVORITES_TYPE);
			}
			group.setColor(MapMarker.getColorId(marker.colorIndex));
		}
		group.setCreationDate(marker.creationDate);
		addToGroupsList(group);
		sortGroups();
		return group;
	}

	private void createHeaderAndHistoryButtonInGroup(@NonNull MapMarkersGroup group) {
		if (group.getName() != null) {
			GroupHeader header = new GroupHeader();
			int type = group.getType();
			if (type != -1) {
				header.setIconRes(type == MarkersSyncGroup.FAVORITES_TYPE
						? R.drawable.ic_action_fav_dark : R.drawable.ic_action_polygom_dark);
			}
			header.setGroup(group);
			group.setGroupHeader(header);
			updateGroup(group);
		}
	}

	private void removeMarkerFromGroup(MapMarker marker) {
		if (marker != null) {
			MapMarkersGroup mapMarkersGroup = getMapMarkerGroupByName(marker.groupName);
			if (mapMarkersGroup != null) {
				mapMarkersGroup.getMarkers().remove(marker);
				updateGroup(mapMarkersGroup);
			}
		}
	}

	private void createMapMarkersGroups() {
		List<MapMarker> markers = new ArrayList<>();
		markers.addAll(mapMarkers);
		markers.addAll(mapMarkersHistory);

		Map<String, MapMarkersGroup> groupsMap = new LinkedHashMap<>();
		MapMarkersGroup noGroup = null;
		for (MapMarker marker : markers) {
			String groupName = marker.groupName;
			if (groupName == null) {
				if (noGroup == null) {
					noGroup = new MapMarkersGroup();
					noGroup.setCreationDate(marker.creationDate);
				}
				noGroup.getMarkers().add(marker);
			} else {
				MapMarkersGroup group = groupsMap.get(groupName);
				if (group == null) {
					group = new MapMarkersGroup();
					group.setName(marker.groupName);
					group.setGroupKey(marker.groupKey);
					MarkersSyncGroup syncGroup = markersDbHelper.getGroup(marker.groupKey);
					if (syncGroup != null) {
						group.setType(syncGroup.getType());
					} else {
						group.setType(MarkersSyncGroup.FAVORITES_TYPE);
					}
					group.setColor(MapMarker.getColorId(marker.colorIndex));
					group.setCreationDate(marker.creationDate);
					groupsMap.put(groupName, group);
				} else {
					long markerCreationDate = marker.creationDate;
					if (markerCreationDate < group.getCreationDate()) {
						group.setCreationDate(markerCreationDate);
					}
				}
				group.getMarkers().add(marker);
			}
		}
		mapMarkersGroups = new ArrayList<>(groupsMap.values());
		if (noGroup != null) {
			addToGroupsList(noGroup);
		}
		sortGroups();

		for (MapMarkersGroup group : mapMarkersGroups) {
			createHeaderAndHistoryButtonInGroup(group);
		}
	}

	private void sortGroups() {
		if (mapMarkersGroups.size() > 0) {
			MapMarkersGroup noGroup = null;
			for (int i = 0; i < mapMarkersGroups.size(); i++) {
				MapMarkersGroup group = mapMarkersGroups.get(i);
				if (group.getName() == null) {
					sortMarkers(group.getMarkers(), false, BY_DATE_ADDED_DESC);
					removeFromGroupsList(group);
					noGroup = group;
				}
			}
			Collections.sort(mapMarkersGroups, new Comparator<MapMarkersGroup>() {
				@Override
				public int compare(MapMarkersGroup group1, MapMarkersGroup group2) {
					long t1 = group1.getCreationDate();
					long t2 = group2.getCreationDate();
					if (t1 > t2) {
						return -1;
					} else if (t1 == t2) {
						return 0;
					} else {
						return 1;
					}
				}
			});
			if (noGroup != null) {
				addToGroupsList(0, noGroup);
			}
		}
	}

	@Nullable
	public MapMarkersGroup getMapMarkerGroupByName(String name) {
		for (MapMarkersGroup group : mapMarkersGroups) {
			if ((name == null && group.getName() == null)
					|| (group.getName() != null && group.getName().equals(name))) {
				return group;
			}
		}
		return null;
	}

	@Nullable
	public MapMarkersGroup getMapMarkerGroupByKey(String key) {
		for (MapMarkersGroup group : mapMarkersGroups) {
			if ((key == null && group.getGroupKey() == null)
					|| (group.getGroupKey() != null && group.getGroupKey().equals(key))) {
				return group;
			}
		}
		return null;
	}

	public static MarkersSyncGroup createGroup(@NonNull FavoriteGroup favGroup) {
		return new MarkersSyncGroup(favGroup.name, favGroup.name, MarkersSyncGroup.FAVORITES_TYPE);
	}

	public static MarkersSyncGroup createGroup(@NonNull File gpx) {
		return new MarkersSyncGroup(gpx.getAbsolutePath(), AndroidUtils.trimExtension(gpx.getName()), MarkersSyncGroup.GPX_TYPE);
	}

	// ---------------------------------------------------------------------------------------------

	// accessors to active markers:

	private void addToMapMarkersList(MapMarker marker) {
		addToMapMarkersList(mapMarkers.size(), marker);
	}

	private void addToMapMarkersList(int position, MapMarker marker) {
		List<MapMarker> copyList = new ArrayList<>(mapMarkers);
		copyList.add(position, marker);
		mapMarkers = copyList;
	}

	private void addToMapMarkersList(List<MapMarker> markers) {
		addToMapMarkersList(mapMarkers.size(), markers);
	}

	private void addToMapMarkersList(int position, List<MapMarker> markers) {
		List<MapMarker> copyList = new ArrayList<>(mapMarkers);
		copyList.addAll(position, markers);
		mapMarkers = copyList;
	}

	private void removeFromMapMarkersList(MapMarker marker) {
		List<MapMarker> copyList = new ArrayList<>(mapMarkers);
		copyList.remove(marker);
		mapMarkers = copyList;
	}

	private void removeFromMapMarkersList(List<MapMarker> markers) {
		List<MapMarker> copyList = new ArrayList<>(mapMarkers);
		copyList.removeAll(markers);
		mapMarkers = copyList;
	}

	// accessors to history markers:

	private void addToMapMarkersHistoryList(MapMarker marker) {
		List<MapMarker> copyList = new ArrayList<>(mapMarkersHistory);
		copyList.add(marker);
		mapMarkersHistory = copyList;
	}

	private void addToMapMarkersHistoryList(List<MapMarker> markers) {
		List<MapMarker> copyList = new ArrayList<>(mapMarkersHistory);
		copyList.addAll(markers);
		mapMarkersHistory = copyList;
	}

	private void removeFromMapMarkersHistoryList(MapMarker marker) {
		List<MapMarker> copyList = new ArrayList<>(mapMarkersHistory);
		copyList.remove(marker);
		mapMarkersHistory = copyList;
	}

	private void removeFromMapMarkersHistoryList(List<MapMarker> markers) {
		List<MapMarker> copyList = new ArrayList<>(mapMarkersHistory);
		copyList.removeAll(markers);
		mapMarkersHistory = copyList;
	}

	// accessors to markers groups:

	private void addToGroupsList(MapMarkersGroup group) {
		addToGroupsList(mapMarkersGroups.size(), group);
	}

	private void addToGroupsList(int position, MapMarkersGroup group) {
		List<MapMarkersGroup> copyList = new ArrayList<>(mapMarkersGroups);
		copyList.add(position, group);
		mapMarkersGroups = copyList;
	}

	private void removeFromGroupsList(MapMarkersGroup group) {
		List<MapMarkersGroup> copyList = new ArrayList<>(mapMarkersGroups);
		copyList.remove(group);
		mapMarkersGroups = copyList;
	}

	// ---------------------------------------------------------------------------------------------

	// classes and interfaces:

	public interface MapMarkerChangedListener {
		void onMapMarkerChanged(MapMarker mapMarker);

		void onMapMarkersChanged();
	}

	public interface OnGroupSyncedListener {
		void onSyncDone();
	}

	private class SyncGroupTask extends AsyncTask<Void, Void, Void> {

		private MarkersSyncGroup group;
		private boolean enabled;
		private OnGroupSyncedListener listener;

		SyncGroupTask(MarkersSyncGroup group, boolean enabled, OnGroupSyncedListener listener) {
			this.group = group;
			this.enabled = enabled;
			this.listener = listener;
		}

		@Override
		protected Void doInBackground(Void... voids) {
			runGroupSynchronization();
			return null;
		}

		private void runGroupSynchronization() {
			if (!isGroupSynced(group.getId())) {
				return;
			}

			List<MapMarker> dbMarkers = markersDbHelper.getMarkersFromGroup(group);

			if (group.getType() == MarkersSyncGroup.FAVORITES_TYPE) {
				FavoriteGroup favGroup = ctx.getFavorites().getGroup(group.getName());
				if (favGroup == null) {
					return;
				}
				if (!favGroup.visible) {
					removeActiveMarkersFromSyncGroup(group.getId());
					removeActiveMarkersFromGroup(group.getId());
					return;
				}

				for (FavouritePoint fp : favGroup.points) {
					addNewMarkerIfNeeded(group, dbMarkers, new LatLon(fp.getLatitude(), fp.getLongitude()), fp.getName(), enabled, fp, null);
				}

				removeOldMarkersIfNeeded(dbMarkers);
			} else if (group.getType() == MarkersSyncGroup.GPX_TYPE) {
				GpxSelectionHelper gpxHelper = ctx.getSelectedGpxHelper();
				File file = new File(group.getId());
				if (!file.exists()) {
					return;
				}

				SelectedGpxFile selectedGpxFile = gpxHelper.getSelectedFileByPath(group.getId());
				GPXFile gpx = selectedGpxFile == null ? null : selectedGpxFile.getGpxFile();
				if (gpx == null) {
					removeActiveMarkersFromSyncGroup(group.getId());
					removeActiveMarkersFromGroup(group.getId());
					return;
				}

				boolean addAll = group.wptCategories == null || group.wptCategories.isEmpty();
				List<WptPt> gpxPoints = new ArrayList<>(gpx.getPoints());
				for (WptPt pt : gpxPoints) {
					if (addAll || group.wptCategories.contains(pt.category)
							|| (pt.category == null && group.wptCategories.contains(""))) {
						addNewMarkerIfNeeded(group, dbMarkers, new LatLon(pt.lat, pt.lon), pt.name, enabled, null, pt);
					}
				}

				removeOldMarkersIfNeeded(dbMarkers);
			}
		}

		@Override
		protected void onPostExecute(Void aVoid) {
			if (listener != null) {
				ctx.runInUIThread(new Runnable() {
					@Override
					public void run() {
						listener.onSyncDone();
					}
				});
			}
		}
	}

	public static class MapMarkersGroup {

		private String name;
		private String groupKey;
		private int type = -1;
		private long creationDate;
		private int color;
		private boolean disabled;
		private List<MapMarker> markers = new ArrayList<>();
		private GroupHeader header;
		private ShowHideHistoryButton showHideHistoryButton;

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getGroupKey() {
			return groupKey;
		}

		public void setGroupKey(String groupKey) {
			this.groupKey = groupKey;
		}

		public int getType() {
			return type;
		}

		public void setType(int type) {
			this.type = type;
		}

		public long getCreationDate() {
			return creationDate;
		}

		public void setCreationDate(long creationDate) {
			this.creationDate = creationDate;
		}

		public int getColor() {
			return color;
		}

		public void setColor(int color) {
			this.color = color;
		}

		public boolean isDisabled() {
			return disabled;
		}

		public void setDisabled(boolean disabled) {
			this.disabled = disabled;
		}

		public List<MapMarker> getMarkers() {
			return markers;
		}

		public void setMarkers(List<MapMarker> markers) {
			this.markers = markers;
		}

		public GroupHeader getGroupHeader() {
			return header;
		}

		public void setGroupHeader(GroupHeader header) {
			this.header = header;
		}

		public ShowHideHistoryButton getShowHideHistoryButton() {
			return showHideHistoryButton;
		}

		public void setShowHideHistoryButton(ShowHideHistoryButton showHideHistoryButton) {
			this.showHideHistoryButton = showHideHistoryButton;
		}

		public List<MapMarker> getActiveMarkers() {
			List<MapMarker> markers = new ArrayList<>(this.markers);
			List<MapMarker> activeMarkers = new ArrayList<>(markers.size());
			for (MapMarker marker : markers) {
				if (!marker.history) {
					activeMarkers.add(marker);
				}
			}
			return activeMarkers;
		}

		public List<MapMarker> getHistoryMarkers() {
			List<MapMarker> historyMarkers = new ArrayList<>();
			for (MapMarker marker : markers) {
				if (marker.history) {
					historyMarkers.add(marker);
				}
			}
			return historyMarkers;
		}
	}

	public static class ShowHideHistoryButton {
		private boolean showHistory;

		public boolean isShowHistory() {
			return showHistory;
		}

		public void setShowHistory(boolean showHistory) {
			this.showHistory = showHistory;
		}
	}

	public static class GroupHeader {
		private int iconRes;
		private MapMarkersGroup group;

		public int getIconRes() {
			return iconRes;
		}

		public void setIconRes(int iconRes) {
			this.iconRes = iconRes;
		}

		public MapMarkersGroup getGroup() {
			return group;
		}

		public void setGroup(MapMarkersGroup group) {
			this.group = group;
		}
	}

	public static class MarkersSyncGroup {

		public static final int FAVORITES_TYPE = 0;
		public static final int GPX_TYPE = 1;

		public static final String MARKERS_SYNC_GROUP_ID = "markers_sync_group_id";

		private String id;
		private String name;
		private int type;
		private Set<String> wptCategories;

		public MarkersSyncGroup(@NonNull String id, @NonNull String name, int type) {
			init(id, name, type, null);
		}

		public MarkersSyncGroup(@NonNull String id, @NonNull String name, int type, @Nullable Set<String> wptCategories) {
			init(id, name, type, wptCategories);
		}

		private void init(String id, String name, int type, Set<String> wptCategories) {
			this.id = id;
			this.name = name;
			this.type = type;
			this.wptCategories = wptCategories;
		}

		public String getId() {
			return id;
		}

		public String getName() {
			return name;
		}

		public int getType() {
			return type;
		}

		public String getWptCategoriesString() {
			if (wptCategories != null) {
				return Algorithms.encodeStringSet(wptCategories);
			}
			return null;
		}
	}

	public static class MapMarker implements LocationPoint {
		private static int[] colors;

		public String id;
		public LatLon point;
		private PointDescription pointDescription;
		public int colorIndex;
		public int index;
		public boolean history;
		public boolean selected;
		public int dist;
		public long creationDate;
		public long visitedDate;
		public String nextKey;
		public String groupKey;
		public String groupName;
		public WptPt wptPt;
		public FavouritePoint favouritePoint;
		public String mapObjectName;

		public MapMarker(LatLon point, PointDescription name, int colorIndex, boolean selected, int index) {
			this.point = point;
			this.pointDescription = name;
			this.colorIndex = colorIndex;
			this.selected = selected;
			this.index = index;
		}

		public PointDescription getPointDescription(Context ctx) {
			return new PointDescription(POINT_TYPE_MAP_MARKER, ctx.getString(R.string.map_marker), getOnlyName());
		}

		public String getName(Context ctx) {
			String name;
			PointDescription pd = getPointDescription(ctx);
			if (Algorithms.isEmpty(pd.getName())) {
				name = pd.getTypeName();
			} else {
				name = pd.getName();
			}
			return name;
		}

		public PointDescription getOriginalPointDescription() {
			return pointDescription;
		}

		public String getOnlyName() {
			return pointDescription == null ? "" : pointDescription.getName();
		}

		public void setName(String name) {
			pointDescription.setName(name);
		}

		public double getLatitude() {
			return point.getLatitude();
		}

		public double getLongitude() {
			return point.getLongitude();
		}

		@Override
		public int getColor() {
			return 0;
		}

		@Override
		public boolean isVisible() {
			return false;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			MapMarker mapMarker = (MapMarker) o;

			return colorIndex == mapMarker.colorIndex && point.equals(mapMarker.point);
		}

		@Override
		public int hashCode() {
			int result = point.hashCode();
			result = 31 * result + colorIndex;
			return result;
		}

		private static final int[] colorsIds = new int[]{
				R.color.marker_blue,
				R.color.marker_green,
				R.color.marker_orange,
				R.color.marker_red,
				R.color.marker_yellow,
				R.color.marker_teal,
				R.color.marker_purple
		};

		public static int[] getColors(Context context) {
			if (colors != null) {
				return colors;
			}
			colors = new int[colorsIds.length];
			for (int i = 0; i < colorsIds.length; i++) {
				colors[i] = ContextCompat.getColor(context, colorsIds[i]);
			}
			return colors;
		}

		public static int getColorId(int colorIndex) {
			return (colorIndex >= 0 && colorIndex < colorsIds.length) ? colorsIds[colorIndex] : colorsIds[0];
		}
	}
}
