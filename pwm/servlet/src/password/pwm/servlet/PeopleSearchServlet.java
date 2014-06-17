/*
 * Password Management Servlets (PWM)
 * http://code.google.com/p/pwm/
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2014 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package password.pwm.servlet;

import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.novell.ldapchai.ChaiUser;
import com.novell.ldapchai.exception.ChaiOperationException;
import com.novell.ldapchai.exception.ChaiUnavailableException;
import com.novell.ldapchai.provider.ChaiProvider;
import password.pwm.*;
import password.pwm.bean.SessionStateBean;
import password.pwm.bean.UserIdentity;
import password.pwm.bean.UserInfoBean;
import password.pwm.bean.servlet.PeopleSearchBean;
import password.pwm.config.Configuration;
import password.pwm.config.FormConfiguration;
import password.pwm.config.PwmSetting;
import password.pwm.error.ErrorInformation;
import password.pwm.error.PwmError;
import password.pwm.error.PwmOperationalException;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.ldap.LdapUserDataReader;
import password.pwm.ldap.UserDataReader;
import password.pwm.ldap.UserSearchEngine;
import password.pwm.ldap.UserStatusReader;
import password.pwm.util.Helper;
import password.pwm.util.PwmLogger;
import password.pwm.util.ServletHelper;
import password.pwm.util.TimeDuration;
import password.pwm.util.macro.MacroMachine;
import password.pwm.util.stats.Statistic;
import password.pwm.ws.server.RestResultBean;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;

public class PeopleSearchServlet extends TopServlet {

    private static final PwmLogger LOGGER = PwmLogger.getLogger(PeopleSearchServlet.class);

    public static class AttributeDetailBean implements Serializable {
        private String name;
        private String label;
        private FormConfiguration.Type type;
        private String value;
        private List<UserReferenceBean> userReferences;

        public String getName()
        {
            return name;
        }

        public void setName(String name)
        {
            this.name = name;
        }

        public String getLabel()
        {
            return label;
        }

        public void setLabel(String label)
        {
            this.label = label;
        }

        public FormConfiguration.Type getType()
        {
            return type;
        }

        public void setType(FormConfiguration.Type type)
        {
            this.type = type;
        }

        public String getValue()
        {
            return value;
        }

        public void setValue(String value)
        {
            this.value = value;
        }

        public List<UserReferenceBean> getUserReferences()
        {
            return userReferences;
        }

        public void setUserReferences(List<UserReferenceBean> userReferences)
        {
            this.userReferences = userReferences;
        }
    }

    public static class UserReferenceBean implements Serializable {
        private String userKey;
        private String display;

        public String getUserKey()
        {
            return userKey;
        }

        public void setUserKey(String userKey)
        {
            this.userKey = userKey;
        }

        public String getDisplay()
        {
            return display;
        }

        public void setDisplay(String display)
        {
            this.display = display;
        }
    }


    @Override
    protected void processRequest(
            final HttpServletRequest req,
            final HttpServletResponse resp
    )
            throws ServletException, IOException, ChaiUnavailableException, PwmUnrecoverableException
    {
        final PwmSession pwmSession = PwmSession.getPwmSession(req);
        final PwmApplication pwmApplication = ContextManager.getPwmApplication(req);
        final SessionStateBean ssBean = pwmSession.getSessionStateBean();

        if (!pwmApplication.getConfig().readSettingAsBoolean(PwmSetting.PEOPLE_SEARCH_ENABLE)) {
            ssBean.setSessionError(new ErrorInformation(PwmError.ERROR_SERVICE_NOT_AVAILABLE));
            ServletHelper.forwardToErrorPage(req, resp, this.getServletContext());
            return;
        }

        if (!pwmSession.getSessionManager().checkPermission(pwmApplication, Permission.PEOPLE_SEARCH)) {
            ssBean.setSessionError(new ErrorInformation(PwmError.ERROR_UNAUTHORIZED));
            ServletHelper.forwardToErrorPage(req, resp, this.getServletContext());
            return;
        }

        final String username = Validator.readStringFromRequest(req, "username");
        if (username != null && username.length() > 0) {
            ((PeopleSearchBean) pwmSession.getSessionBean(PeopleSearchBean.class)).setSearchString(username);
        }

        {
            final List<FormConfiguration> searchForm = pwmApplication.getConfig().readSettingAsForm(
                    PwmSetting.PEOPLE_SEARCH_RESULT_FORM);
            final Map<String, String> searchColumns = new LinkedHashMap<String, String>();
            for (final FormConfiguration formConfiguration : searchForm) {
                searchColumns.put(formConfiguration.getName(),
                        formConfiguration.getLabel(pwmSession.getSessionStateBean().getLocale()));
            }
            ((PeopleSearchBean) pwmSession.getSessionBean(PeopleSearchBean.class)).setSearchColumnHeaders(
                    searchColumns);
        }


        final String processRequestParam = Validator.readStringFromRequest(req, PwmConstants.PARAM_ACTION_REQUEST);
        if (processRequestParam != null && processRequestParam.length() > 0) {
            Validator.validatePwmFormID(req);
            if (processRequestParam.equals("search")) {
                restSearchRequest(req, resp, pwmApplication, pwmSession);
                return;
            } else if (processRequestParam.equals("detail")) {
                restUserDetailRequest(req, resp, pwmApplication, pwmSession);
                return;
            } else if (processRequestParam.equals("photo")) {
                processUserPhotoImageRequest(req, resp, pwmApplication, pwmSession);
                return;
            }
        }

        ServletHelper.forwardToJsp(req, resp, PwmConstants.JSP_URL.PEOPLE_SEARCH);
    }

    private void restSearchRequest(
            final HttpServletRequest req,
            final HttpServletResponse resp,
            final PwmApplication pwmApplication,
            final PwmSession pwmSession
    )
            throws ChaiUnavailableException, PwmUnrecoverableException, IOException, ServletException
    {
        final Date startTime = new Date();
        final String bodyString = ServletHelper.readRequestBody(req);
        final Map<String, String> valueMap = Helper.getGson().fromJson(bodyString,
                new TypeToken<Map<String, String>>() {
                }.getType()
        );

        final String username = valueMap.get("username");
        PeopleSearchBean peopleSearchBean = ((PeopleSearchBean) pwmSession.getSessionBean(PeopleSearchBean.class));
        peopleSearchBean.setSearchString(username);
        final boolean useProxy = pwmApplication.getConfig().readSettingAsBoolean(PwmSetting.PEOPLE_SEARCH_USE_PROXY);
        final String cacheID = "ps:search:"
                + (useProxy ? "" : pwmSession.getUserInfoBean().getUserIdentity().toDeliminatedKey())
                + ":" + username;

        {
            final String cachedOutput = pwmApplication.getCacheService().get(cacheID);
            if (cachedOutput != null) {
                final HashMap<String, Object> resultOutput = Helper.getGson().fromJson(cachedOutput,
                        new TypeToken<HashMap<String, Object>>() {
                        }.getType());
                final RestResultBean restResultBean = new RestResultBean();
                restResultBean.setData(resultOutput);
                ServletHelper.outputJsonResult(resp, restResultBean);
                LOGGER.trace(pwmSession, "finished rest peoplesearch search using CACHE in " + TimeDuration.fromCurrent(
                        startTime).asCompactString() + ", size=" + resultOutput.size());

                if (pwmApplication.getStatisticsManager() != null) {
                    pwmApplication.getStatisticsManager().incrementValue(Statistic.PEOPLESEARCH_SEARCHES);
                }

                return;
            }
        }

        final List<FormConfiguration> searchForm = pwmApplication.getConfig().readSettingAsForm(
                PwmSetting.PEOPLE_SEARCH_RESULT_FORM);
        final int maxResults = (int) pwmApplication.getConfig().readSettingAsLong(
                PwmSetting.PEOPLE_SEARCH_RESULT_LIMIT);

        if (peopleSearchBean.getSearchString() == null || peopleSearchBean.getSearchString().length() < 1) {
            final HashMap<String, Object> emptyResults = new HashMap<String, Object>();
            emptyResults.put("searchResults", new ArrayList<Map<String, String>>());
            emptyResults.put("sizeExceeded", false);
            final RestResultBean restResultBean = new RestResultBean();
            restResultBean.setData(emptyResults);
            ServletHelper.outputJsonResult(resp, restResultBean);
            return;
        }

        final UserSearchEngine userSearchEngine = new UserSearchEngine(pwmApplication);
        final UserSearchEngine.SearchConfiguration searchConfiguration = new UserSearchEngine.SearchConfiguration();
        searchConfiguration.setContexts(
                pwmApplication.getConfig().readSettingAsStringArray(PwmSetting.PEOPLE_SEARCH_SEARCH_BASE));
        searchConfiguration.setEnableContextValidation(false);
        searchConfiguration.setUsername(username);
        searchConfiguration.setEnableValueEscaping(false);
        searchConfiguration.setFilter(
                pwmApplication.getConfig().readSettingAsString(PwmSetting.PEOPLE_SEARCH_SEARCH_FILTER));
        if (!useProxy) {
            searchConfiguration.setLdapProfile(pwmSession.getUserInfoBean().getUserIdentity().getLdapProfileID());
            searchConfiguration.setChaiProvider(pwmSession.getSessionManager().getChaiProvider(pwmApplication));
        }

        final UserSearchEngine.UserSearchResults results;
        final boolean sizeExceeded;
        try {
            results = userSearchEngine.performMultiUserSearchFromForm(pwmSession, searchConfiguration, maxResults,
                    searchForm);
            sizeExceeded = results.isSizeExceeded();
        } catch (PwmOperationalException e) {
            final ErrorInformation errorInformation = e.getErrorInformation();
            LOGGER.error(pwmSession, errorInformation.toDebugStr());
            final RestResultBean restResultBean = new RestResultBean();
            restResultBean.setData(new ArrayList<Map<String, String>>());
            ServletHelper.outputJsonResult(resp, restResultBean);
            return;
        }


        final RestResultBean restResultBean = new RestResultBean();
        final LinkedHashMap<String, Object> outputData = new LinkedHashMap<String, Object>();
        outputData.put("searchResults",
                new ArrayList<Map<String, String>>(results.resultsAsJsonOutput(pwmApplication)));
        outputData.put("sizeExceeded", sizeExceeded);
        restResultBean.setData(outputData);
        ServletHelper.outputJsonResult(resp, restResultBean);
        final long maxCacheSeconds = pwmApplication.getConfig().readSettingAsLong(PwmSetting.PEOPLE_SEARCH_MAX_CACHE_SECONDS);
        if (maxCacheSeconds > 0) {
            final Date expiration = new Date(System.currentTimeMillis() * maxCacheSeconds * 1000);
            pwmApplication.getCacheService().put(cacheID,expiration,Helper.getGson().toJson(outputData));
        }

        if (pwmApplication.getStatisticsManager() != null) {
            pwmApplication.getStatisticsManager().incrementValue(Statistic.PEOPLESEARCH_SEARCHES);
        }

        LOGGER.trace(pwmSession, "finished rest peoplesearch search in " + TimeDuration.fromCurrent(
                startTime).asCompactString() + ", size=" + results.getResults().size());
    }

    private static UserSearchEngine.UserSearchResults doDetailLookup(
            final PwmApplication pwmApplication,
            final PwmSession pwmSession,
            final UserIdentity userIdentity
    )
            throws PwmUnrecoverableException, ChaiUnavailableException
    {
        final Configuration config = pwmApplication.getConfig();
        final List<FormConfiguration> detailFormConfig = config.readSettingAsForm(PwmSetting.PEOPLE_SEARCH_DETAIL_FORM);
        final Map<String, String> attributeHeaderMap = UserSearchEngine.UserSearchResults.fromFormConfiguration(
                detailFormConfig, pwmSession.getSessionStateBean().getLocale());
        final ChaiUser theUser = config.readSettingAsBoolean(PwmSetting.PEOPLE_SEARCH_USE_PROXY)
                ? pwmApplication.getProxiedChaiUser(userIdentity)
                : pwmSession.getSessionManager().getActor(pwmApplication, userIdentity);
        Map<String, String> values = null;
        try {
            values = theUser.readStringAttributes(attributeHeaderMap.keySet());
        } catch (ChaiOperationException e) {
            LOGGER.error("unexpected error during detail lookup of '" + userIdentity + "', error: " + e.getMessage());
        }
        return new UserSearchEngine.UserSearchResults(attributeHeaderMap,
                Collections.singletonMap(userIdentity, values), false);
    }


    private void restUserDetailRequest(
            final HttpServletRequest req,
            final HttpServletResponse resp,
            final PwmApplication pwmApplication,
            final PwmSession pwmSession
    )
            throws ChaiUnavailableException, PwmUnrecoverableException, IOException, ServletException
    {
        final Date startTime = new Date();
        final String bodyString = ServletHelper.readRequestBody(req);
        final Map<String, String> valueMap = Helper.getGson().fromJson(bodyString,
                new TypeToken<Map<String, String>>() {
                }.getType()
        );

        if (valueMap == null) {
            return;
        }

        final String userKey = valueMap.get("userKey");
        if (userKey == null || userKey.isEmpty()) {
            return;
        }

        final boolean useProxy = pwmApplication.getConfig().readSettingAsBoolean(PwmSetting.PEOPLE_SEARCH_USE_PROXY);
        final UserIdentity userIdentity = UserIdentity.fromKey(userKey, pwmApplication.getConfig());

        final String cacheID = "ps:detail:"
                + (useProxy ? "" : pwmSession.getUserInfoBean().getUserIdentity().toDeliminatedKey())
                + ":" + userIdentity.toDeliminatedKey();
        {
            final String cachedOutput = pwmApplication.getCacheService().get(cacheID);
            if (cachedOutput != null) {
                final HashMap<String, Object> resultOutput = Helper.getGson().fromJson(cachedOutput,
                        new TypeToken<HashMap<String, Object>>() {
                        }.getType());
                final RestResultBean restResultBean = new RestResultBean();
                restResultBean.setData(resultOutput);
                ServletHelper.outputJsonResult(resp, restResultBean);
                LOGGER.trace(pwmSession, "finished rest detail request in " + TimeDuration.fromCurrent(
                        startTime).asCompactString() + "using CACHED details, results=" + Helper.getGson(
                        new GsonBuilder().disableHtmlEscaping()).toJson(restResultBean));

                if (pwmApplication.getStatisticsManager() != null) {
                    pwmApplication.getStatisticsManager().incrementValue(Statistic.PEOPLESEARCH_DETAILS);
                }

                return;
            }
        }
        try {
            checkIfUserIdentityPermitted(pwmApplication, pwmSession, userIdentity);
        } catch (PwmOperationalException e) {
            ServletHelper.outputJsonResult(resp,RestResultBean.fromError(e.getErrorInformation()));
        }

        UserSearchEngine.UserSearchResults detailResults = doDetailLookup(pwmApplication, pwmSession, userIdentity);
        final Map<String, String> searchResults = detailResults.getResults().get(userIdentity);

        final LinkedHashMap<String, Object> resultOutput = new LinkedHashMap<String, Object>();
        final List<FormConfiguration> detailFormConfig = pwmApplication.getConfig().readSettingAsForm(
                PwmSetting.PEOPLE_SEARCH_DETAIL_FORM);
        List<AttributeDetailBean> bean = convertResultMapToBean(pwmApplication, pwmSession, userIdentity,
                detailFormConfig, searchResults);


        resultOutput.put("detail", bean);
        final String photoURL = figurePhotoURL(pwmApplication, pwmSession, userIdentity);
        if (photoURL != null) {
            resultOutput.put("photoURL", photoURL);
        }
        final String displayName = figureDisplaynameValue(pwmApplication, pwmSession, userIdentity);
        if (displayName != null) {
            resultOutput.put("displayName", displayName);
        }

        final RestResultBean restResultBean = new RestResultBean();
        restResultBean.setData(resultOutput);
        ServletHelper.outputJsonResult(resp, restResultBean);
        LOGGER.trace(pwmSession, "finished rest detail request in " + TimeDuration.fromCurrent(
                startTime).asCompactString() + ", results=" + Helper.getGson(
                new GsonBuilder().disableHtmlEscaping()).toJson(restResultBean));

        final long maxCacheSeconds = pwmApplication.getConfig().readSettingAsLong(PwmSetting.PEOPLE_SEARCH_MAX_CACHE_SECONDS);
        if (maxCacheSeconds > 0) {
            final Date expiration = new Date(System.currentTimeMillis() * maxCacheSeconds * 1000);
            pwmApplication.getCacheService().put(cacheID,expiration,Helper.getGson().toJson(resultOutput));
        }

        if (pwmApplication.getStatisticsManager() != null) {
            pwmApplication.getStatisticsManager().incrementValue(Statistic.PEOPLESEARCH_DETAILS);
        }
    }


    private static String figurePhotoURL(
            final PwmApplication pwmApplication,
            final PwmSession pwmSession,
            final UserIdentity userIdentity
    )
            throws PwmUnrecoverableException, ChaiUnavailableException
    {
        final String overrideURL = pwmApplication.getConfig().readSettingAsString(PwmSetting.PEOPLE_SEARCH_PHOTO_URL_OVERRIDE);
        if (overrideURL != null && !overrideURL.isEmpty()) {
            final MacroMachine macroMachine = getMacroMachine(pwmApplication, pwmSession, userIdentity);
            return macroMachine.expandMacros(overrideURL);
        }

        final String attribute = pwmApplication.getConfig().readSettingAsString(PwmSetting.PEOPLE_SEARCH_PHOTO_ATTRIBUTE);
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }

        return "PeopleSearch?processAction=photo&userKey=" + userIdentity.toObfuscatedKey(pwmApplication.getConfig());
    }

    private static String figureDisplaynameValue(
            final PwmApplication pwmApplication,
            final PwmSession pwmSession,
            final UserIdentity userIdentity
    )
            throws PwmUnrecoverableException, ChaiUnavailableException
    {
        final MacroMachine macroMachine = getMacroMachine(pwmApplication, pwmSession, userIdentity);
        final String settingValue = pwmApplication.getConfig().readSettingAsString(
                PwmSetting.PEOPLE_SEARCH_DISPLAY_NAME);
        return macroMachine.expandMacros(settingValue);
    }


    private void processUserPhotoImageRequest(
            final HttpServletRequest req,
            final HttpServletResponse resp,
            final PwmApplication pwmApplication,
            final PwmSession pwmSession
    )
            throws ChaiUnavailableException, PwmUnrecoverableException, IOException, ServletException
    {
        final String userKey = Validator.readStringFromRequest(req, "userKey");
        if (userKey.length() < 1) {
            pwmSession.getSessionStateBean().setSessionError(
                    new ErrorInformation(PwmError.ERROR_MISSING_PARAMETER, "userKey parameter is missing"));
            ServletHelper.forwardToErrorPage(req, resp, false);
            return;
        }

        final String attribute = pwmApplication.getConfig().readSettingAsString(PwmSetting.PEOPLE_SEARCH_PHOTO_ATTRIBUTE);
        if (attribute == null || attribute.isEmpty()) {
            return;
        }

        final UserIdentity userIdentity = UserIdentity.fromKey(userKey, pwmApplication.getConfig());
        try {
            checkIfUserIdentityPermitted(pwmApplication, pwmSession, userIdentity);
        } catch (PwmOperationalException e) {
            ServletHelper.outputJsonResult(resp,RestResultBean.fromError(e.getErrorInformation()));
        }

        LOGGER.info(pwmSession,
                "received user photo request by " + pwmSession.getUserInfoBean().getUserIdentity().toString() + " for user " + userIdentity.toString());

        try {
            final ChaiUser chaiUser = getChaiUser(pwmApplication, pwmSession, userIdentity);
            byte[][] photoData = chaiUser.readMultiByteAttribute("photo");
            if (photoData != null && photoData.length > 0) {
                resp.getOutputStream().write(photoData[0]);
                resp.getOutputStream().close();
            }
            resp.setHeader("Cache-Control", "cache-control: private, max-age=3600");
        } catch (ChaiOperationException e) {
            e.printStackTrace();
        }
    }

    private static List<AttributeDetailBean> convertResultMapToBean(
            final PwmApplication pwmApplication,
            final PwmSession pwmSession,
            final UserIdentity userIdentity,
            List<FormConfiguration> form,
            Map<String, String> searchResults
    )
            throws ChaiUnavailableException, PwmUnrecoverableException
    {
        final int MAX_VALUES = Integer.parseInt(pwmApplication.getConfig().readAppProperty(AppProperty.PEOPLESEARCH_MAX_VALUE_COUNT));
        final List<AttributeDetailBean> returnObj = new ArrayList<AttributeDetailBean>();
        for (FormConfiguration formConfiguration : form) {
            if (formConfiguration.isRequired() || searchResults.containsKey(formConfiguration.getName())) {
                AttributeDetailBean bean = new AttributeDetailBean();
                bean.setName(formConfiguration.getName());
                bean.setLabel(formConfiguration.getLabel(pwmSession.getSessionStateBean().getLocale()));
                bean.setType(formConfiguration.getType());
                if (formConfiguration.getType() == FormConfiguration.Type.userDN) {
                    if (searchResults.containsKey(formConfiguration.getName())) {
                        final ChaiUser chaiUser = getChaiUser(pwmApplication, pwmSession, userIdentity);
                        final Set<String> values;
                        try {
                            values = chaiUser.readMultiStringAttribute(formConfiguration.getName());
                            final List<UserReferenceBean> userReferences = new ArrayList<UserReferenceBean>();
                            for (final String value : values) {
                                if (userReferences.size() < MAX_VALUES) {
                                    final UserIdentity loopIdentity = new UserIdentity(value,
                                            userIdentity.getLdapProfileID());
                                    final String displayValue = figureDisplaynameValue(pwmApplication, pwmSession,
                                            loopIdentity);
                                    final UserReferenceBean userReference = new UserReferenceBean();
                                    userReference.setUserKey(loopIdentity.toObfuscatedKey(pwmApplication.getConfig()));
                                    userReference.setDisplay(displayValue);
                                    userReferences.add(userReference);
                                }
                            }
                            bean.setUserReferences(userReferences);
                        } catch (ChaiOperationException e) {
                            LOGGER.error(pwmSession, "error during user detail lookup: " + e.getMessage());
                        }
                    }
                } else {
                    bean.setValue(searchResults.containsKey(formConfiguration.getName()) ? searchResults.get(
                            formConfiguration.getName()) : "");
                }
                returnObj.add(bean);
            }
        }
        return returnObj;
    }

    private static ChaiUser getChaiUser(
            final PwmApplication pwmApplication,
            final PwmSession pwmSession,
            final UserIdentity userIdentity
    )
            throws ChaiUnavailableException, PwmUnrecoverableException
    {
        return pwmApplication.getConfig().readSettingAsBoolean(PwmSetting.PEOPLE_SEARCH_USE_PROXY)
                ? pwmApplication.getProxiedChaiUser(userIdentity)
                : pwmSession.getSessionManager().getActor(pwmApplication, userIdentity);

    }

    private static MacroMachine getMacroMachine(
            final PwmApplication pwmApplication,
            final PwmSession pwmSession,
            final UserIdentity userIdentity
    )
            throws ChaiUnavailableException, PwmUnrecoverableException
    {
        final ChaiUser chaiUser = getChaiUser(pwmApplication, pwmSession, userIdentity);
        final UserInfoBean userInfoBean;
        if (Boolean.parseBoolean(pwmApplication.getConfig().readAppProperty(AppProperty.PEOPLESEARCH_DISPLAYNAME_USEALLMACROS))) {
            final Locale locale = pwmSession.getSessionStateBean().getLocale();
            final ChaiProvider chaiProvider = pwmApplication.getProxiedChaiUser(userIdentity).getChaiProvider();
            userInfoBean = new UserInfoBean();
            final UserStatusReader userStatusReader = new UserStatusReader(pwmApplication);
            userStatusReader.populateUserInfoBean(pwmSession, userInfoBean, locale, userIdentity, null, chaiProvider);
        } else {
            userInfoBean = null;
        }
        UserDataReader userDataReader = new LdapUserDataReader(userIdentity, chaiUser);
        return new MacroMachine(pwmApplication, userInfoBean, userDataReader);
    }

    private static void checkIfUserIdentityPermitted(
            final PwmApplication pwmApplication,
            final PwmSession pwmSession,
            final UserIdentity userIdentity
    )
            throws ChaiUnavailableException, PwmUnrecoverableException, PwmOperationalException
    {
        final String filterSetting = pwmApplication.getConfig().readSettingAsString(PwmSetting.PEOPLE_SEARCH_SEARCH_FILTER);
        final String queryString = filterSetting.replaceAll("%USERNAME%","*");
        final boolean match = Helper.testQueryMatch(pwmApplication, pwmSession, userIdentity, queryString);
        if (!match) {
            throw new PwmOperationalException(new ErrorInformation(PwmError.ERROR_SERVICE_NOT_AVAILABLE,"requested userDN is not available within configured search filter"));
        }
    }
}