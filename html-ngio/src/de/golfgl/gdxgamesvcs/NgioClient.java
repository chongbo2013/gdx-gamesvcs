package de.golfgl.gdxgamesvcs;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.JsonWriter;

import de.golfgl.gdxgamesvcs.achievement.IFetchAchievementsResponseListener;
import de.golfgl.gdxgamesvcs.gamestate.IFetchGameStatesListResponseListener;
import de.golfgl.gdxgamesvcs.gamestate.ILoadGameStateResponseListener;
import de.golfgl.gdxgamesvcs.gamestate.ISaveGameStateResponseListener;
import de.golfgl.gdxgamesvcs.leaderboard.IFetchLeaderBoardEntriesResponseListener;

/**
 * Newgrounds.io client.
 * <p>
 * See newgrounds.io and newgrounds.com
 * <p>
 * Please note: Although this file would technically work in core module, it is placed in html project because it does
 * not work on other platforms than GWT. NGIO blocks direct calls to the API from other clients than webbrowsers.
 * <p>
 * Created by Benjamin Schulte on 17.06.2017.
 */

public class NgioClient implements IGameServiceClient {

    public static final String GAMESERVICE_ID = IGameServiceClient.GS_NEWGROUNDS_ID;
    public static final String NGIO_GATEWAY = "//www.newgrounds.io/gateway_v3.php";
    public static final String NGIO_SESSIONID_PARAM = "ngio_session_id";

    protected IGameServiceListener gsListener;
    protected String ngAppId;
    protected String sessionId;
    protected boolean initialized;
    protected IGameServiceIdMapper<Integer> boardMapper;
    protected IGameServiceIdMapper<Integer> medalMapper;
    protected String eventHostId;
    private String ngEncryptionKey;
    private boolean connected;
    private boolean connectionPending;
    private String userName;
    private int userId;

    @Override
    public String getGameServiceId() {
        return GAMESERVICE_ID;
    }

    @Override
    public void setListener(IGameServiceListener gsListener) {
        this.gsListener = gsListener;
    }

    /**
     * You need to call this basic initialization in order to call Newgrounds.io
     *
     * @param ngAppId         the id from your NG API tools
     * @param ngioSessionid   session id, obtain it from NGIO_SESSIONID_PARAM of your iframe
     * @param ngEncryptionKey not used at the moment
     * @return this for method chaining
     */
    public NgioClient initialize(String ngAppId, String ngioSessionid, String ngEncryptionKey) {
        this.ngAppId = ngAppId;
        this.sessionId = ngioSessionid;
        this.ngEncryptionKey = ngEncryptionKey;
        this.initialized = true;

        return this;
    }

    /**
     * sets up the mapper for scoreboard calls
     *
     * @param boardMapper
     * @return this for method chaining
     */
    public NgioClient setNgScoreboardMapper(IGameServiceIdMapper<Integer> boardMapper) {
        this.boardMapper = boardMapper;
        return this;
    }

    /**
     * sets up the mapper for medal calls
     *
     * @param medalMapper
     * @return this for method chaining
     */
    public NgioClient setNgMedalMapper(IGameServiceIdMapper<Integer> medalMapper) {
        this.medalMapper = medalMapper;
        return this;
    }

    /**
     * logging events to Newgrounds requires an id for the host.
     * <p>
     * NGIO documentation sais: The domain hosting your app. Example: "newgrounds.com", "localHost", but on GWT
     * you should just use
     * <p>
     * com.google.gwt.user.client.Window.Location.getHostname()
     *
     * @param eventHostId id you want to log to newgrounds
     */
    public NgioClient setEventHostId(String eventHostId) {
        this.eventHostId = eventHostId;
        return this;
    }

    @Override
    public boolean resumeSession() {
        return connect(true);
    }

    @Override
    public boolean logIn() {
        return connect(false);
    }

    public boolean connect(final boolean silent) {
        if (connected)
            return true;

        //TODO: Ping every 5 minutes:
        //Ngio documentation:
        // Remembered sessions will expire after 30 days of inactivity. All other sessions will expire after one hour
        // of inactivity. Session expirations get renewed with every call. You could use 'Gateway.ping' every
        // 5 minutes to keep sessions alive.


        if (!initialized) {
            Gdx.app.error(GAMESERVICE_ID, "Cannot connect before initialize is called.");
            return false;
        }

        if (sessionId == null) {
            Gdx.app.log(GAMESERVICE_ID, "Session id needed to connect to Newgrounds, but not set.");
            return false;
        } else {
            connectionPending = true;

            // yeah, I know I could do that better... but hey, at least it is fast!
            sendToGateway("App.checkSession", null,
                    new RequestResultRunnable() {
                        @Override
                        public void run(String json) {
                            connectionPending = false;
                            checkSessionAnswer(silent, json);
                        }
                    });

            return true;
        }

    }

    protected void checkSessionAnswer(boolean silent, String json) {
        JsonValue root = new JsonReader().parse(json);

        // was the query processed successfully=
        boolean success = root.getBoolean("success", false);

        String errorMsg = "";
        JsonValue errorObject = null;

        if (success) {
            // if the query was successful, maybe session is invalid?
            try {
                JsonValue resultObjectData = root.get("result").get("data");

                connected = resultObjectData.getBoolean("success");

// => Answer when session is invalid:
// {"success":true,"app_id":"46188:ARRyvuAv","result":{"component":"App.checkSession","data":{"success":false,
// "error":{"message":"Session, with id \"aa9f2716754951641744e7628247f54237cb2b8d8bed24\", is invalid or expired.",
// "code":104},"parameters":{}}}}

//=> Answer when session is valid:
// {"success":true,"app_id":"46188:ARRyvuAv","result":{"component":"App.checkSession","data":{"success":true,
// "session":{"id":"aa9f2716754951641744e7628247f54237cb2b8d8bed24","user":{"id":6406923,"name":"MrStahlfelge",
// "icons":{"small":"http:\/\/img.ngfiles.com\/defaults\/icon-user-smallest.gif","medium":"http:\/\/img.ngfiles
// .com\/defaults\/icon-user-smaller.gif","large":"http:\/\/img.ngfiles.com\/defaults\/icon-user.gif"},
// "url":"http:\/\/mrstahlfelge.newgrounds.com","supporter":false},"expired":false,"remember":false}}}}

                if (connected) {
                    JsonValue userData = resultObjectData.get("session").get("user");
                    userName = userData.getString("name");
                    userId = userData.getInt("id");
                } else {
                    errorMsg = "User session invalid";
                    errorObject = resultObjectData.get("error");
                }

            } catch (Throwable t) {
                Gdx.app.error(GAMESERVICE_ID, "Error checking session - could not parse user data");
            }

        } else {
//=> Answer when call is blocked:
// {"success":false,"error":{"message":"You have been making too many calls to the API and have been temporarily
// blocked. Try again in 96 seconds.","code":107},"api_version":"3.0.0","help_url":"http:\/\/www.newgrounds
// .com\/wiki\/creator-resources\/newgrounds-apis\/newgrounds-io"}

            connected = false;

            errorObject = root.get("error");
            errorMsg = "Error checking session";

        }

        if (!connected) {
            if (errorObject != null)
                errorMsg = errorMsg + ": " + errorObject.getInt("code") + "/" + errorObject.getString("message");

            Gdx.app.log(GAMESERVICE_ID, errorMsg);

            if (!silent && gsListener != null)
                gsListener.gsShowErrorToUser(IGameServiceListener.GsErrorType.errorLoginFailed, errorMsg, null);
        }

        if (gsListener != null) {
            if (connected)
                // yeah!
                gsListener.gsOnSessionActive();
            else
                // reset pending state
                gsListener.gsOnSessionInactive();
        }
    }

    @Override
    public void pauseSession() {
        //TODO: Deactivate ping
        connected = false;

        if (gsListener != null)
            gsListener.gsOnSessionInactive();
    }

    @Override
    public void logOff() {
        // changing this value leads to not making any calls to the gateway anymore
        userName = null;
        userId = 0;

        pauseSession();
    }

    @Override
    public String getPlayerDisplayName() {
        return userName;
    }

    @Override
    public boolean isSessionActive() {
        return connected && userId > 0;
    }

    @Override
    public boolean isConnectionPending() {
        return connectionPending && !connected;
    }

    @Override
    public void showLeaderboards(String leaderBoardId) throws GameServiceException {
        throw new GameServiceException.NotSupportedException();
    }

    @Override
    public void showAchievements() throws GameServiceException {
        throw new GameServiceException.NotSupportedException();
    }

    @Override
    public boolean fetchAchievements(IFetchAchievementsResponseListener callback) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean submitToLeaderboard(String leaderboardId, long score, String tag) {
        if (boardMapper == null) {
            Gdx.app.log(GAMESERVICE_ID, "Cannot post score: No mapper for leader board ids provided.");
            return false;
        }

        Integer boardId = boardMapper.mapToGsId(leaderboardId);

        // no board available or not connected
        if (boardId == null || !isSessionActive())
            return false;

        JsonValue parameters = new JsonValue(JsonValue.ValueType.object);
        parameters.addChild("id", new JsonValue(boardId));
        parameters.addChild("value", new JsonValue(score));
        if (tag != null)
            parameters.addChild("tag", new JsonValue(tag));

        sendToGateway("ScoreBoard.postScore", parameters, null);

        return true;
    }

    @Override
    public boolean fetchLeaderboardEntries(String leaderBoardId, int limit, boolean relatedToPlayer,
                                           IFetchLeaderBoardEntriesResponseListener callback) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean submitEvent(String eventId, int increment) {
        // incrementing is not supported by Newgrounds, so we ignore the param

        if (!initialized) {
            Gdx.app.error(GAMESERVICE_ID, "Cannot submit event before initialize() is called.");
            return false;
        }

        if (eventHostId == null) {
            Gdx.app.log(GAMESERVICE_ID, "Cannot post event: No host id for logging events provided.");
            return false;
        }

        JsonValue parameters = new JsonValue(JsonValue.ValueType.object);
        parameters.addChild("event_name", new JsonValue(eventId));
        parameters.addChild("host", new JsonValue(eventHostId));

        sendToGateway("Event.logEvent", parameters, null);

        return true;
    }

    @Override
    public boolean unlockAchievement(String achievementId) {
        if (medalMapper == null) {
            Gdx.app.log(GAMESERVICE_ID, "Cannot unlock achievmenet: No mapper for achievement ids provided.");
            return false;
        }

        Integer medalId = medalMapper.mapToGsId(achievementId);

        // no board available or not connected
        if (medalId == null)
            return false;

        if (!isSessionActive())
            return false;

        JsonValue parameters = new JsonValue(JsonValue.ValueType.object);
        parameters.addChild("id", new JsonValue(medalId));

        sendToGateway("Medal.unlock", parameters, null);

        return true;
    }

    @Override
    public boolean incrementAchievement(String achievementId, int incNum, float completionPercentage) {
        // incrementing is not supported, so fall back
        if (completionPercentage >= 1f)
            return unlockAchievement(achievementId);
        else
            return true;
    }

    @Override
    public void saveGameState(String fileId, byte[] gameState, long progressValue, ISaveGameStateResponseListener
            listener) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void loadGameState(String fileId, ILoadGameStateResponseListener listener) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean deleteGameState(String fileId, ISaveGameStateResponseListener success) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean fetchGameStates(IFetchGameStatesListResponseListener callback) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isFeatureSupported(GameServiceFeature feature) {
        return feature.equals(GameServiceFeature.SubmitEvents);
    }

    /**
     * Call newgrounds.io gateway
     *
     * @param component  see http://www.newgrounds.io/help/components/
     * @param parameters also see NG doc
     * @param req        callback object
     */
    protected void sendToGateway(String component, JsonValue parameters, RequestResultRunnable req) {
        // if no callback is needed, provide a no-op callback
        if (req == null)
            req = new RequestResultRunnable() {
                @Override
                public void run(String json) {
                }
            };

        sendForm("{\"app_id\": \"" + ngAppId + "\",\"session_id\":\"" + sessionId + "\","
                        + "\"call\": {\"component\": \"" + component + "\",\"parameters\": " +
                        (parameters == null ? "{}" : parameters.toJson(JsonWriter.OutputType.json)) + "}}\n",
                req);
    }

    /**
     * For some reason, calls to NGIO must be form-encoded. So we use a native call to let the browser do the
     * encoding stuff. Copied from the newgrounds example.
     *
     * @param json
     */
    private native void sendForm(String json, NgioClient.RequestResultRunnable resultRun) /*-{
        var xhr = new XMLHttpRequest();

		xhr.onreadystatechange = function() {
			if (xhr.readyState==4) {
				resultRun.@de.golfgl.gdxgamesvcs.NgioClient.RequestResultRunnable::run(Ljava/lang/String;)(xhr
				.responseText);
			}
		};

		var formData = new FormData();
        formData.append('input', json);
		xhr.open('POST', '//www.newgrounds.io/gateway_v3.php', true);
		xhr.send(formData);

        }-*/;

    public static abstract class RequestResultRunnable {
        abstract public void run(String json);
    }
}
