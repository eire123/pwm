/*
 * Password Management Servlets (PWM)
 * http://code.google.com/p/pwm/
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2010 The PWM Project
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

import com.novell.ldapchai.ChaiFactory;
import com.novell.ldapchai.ChaiUser;
import com.novell.ldapchai.cr.*;
import com.novell.ldapchai.exception.ChaiErrorCode;
import com.novell.ldapchai.exception.ChaiOperationException;
import com.novell.ldapchai.exception.ChaiUnavailableException;
import com.novell.ldapchai.exception.ChaiValidationException;
import com.novell.ldapchai.provider.ChaiProvider;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import password.pwm.*;
import password.pwm.bean.SessionStateBean;
import password.pwm.bean.SetupResponsesBean;
import password.pwm.bean.UserInfoBean;
import password.pwm.config.Message;
import password.pwm.config.PwmSetting;
import password.pwm.error.ErrorInformation;
import password.pwm.error.PwmError;
import password.pwm.error.PwmException;
import password.pwm.util.PwmLogger;
import password.pwm.util.stats.Statistic;
import password.pwm.wordlist.WordlistManager;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;

/**
 * User interaction servlet for setting up secret question/answer
 *
 * @author Jason D. Rivard
 */
public class SetupResponsesServlet extends TopServlet {
// ------------------------------ FIELDS ------------------------------

    private static final PwmLogger LOGGER = PwmLogger.getLogger(SetupResponsesServlet.class);

// -------------------------- OTHER METHODS --------------------------

    protected void processRequest(
            final HttpServletRequest req,
            final HttpServletResponse resp
    )
            throws ServletException, ChaiUnavailableException, IOException, PwmException
    {
        // fetch the required beans / managers
        final PwmSession pwmSession = PwmSession.getPwmSession(req);
        final SessionStateBean ssBean = pwmSession.getSessionStateBean();
        final UserInfoBean uiBean = pwmSession.getUserInfoBean();
        final ChallengeSet assignedCs = uiBean.getChallengeSet();

        // read the action request parameter
        final String processRequestParam = Validator.readStringFromRequest(req, PwmConstants.PARAM_ACTION_REQUEST, 255);

        // check to see if the user is permitted to setup responses
        if (!Permission.checkPermission(Permission.SETUP_RESPONSE, pwmSession)) {
            ssBean.setSessionError(new ErrorInformation(PwmError.ERROR_UNAUTHORIZED));
            Helper.forwardToErrorPage(req, resp, this.getServletContext());
            return;
        }

        // check to see if the user has any challenges assigned
        if (assignedCs == null || assignedCs.getChallenges().isEmpty()) {
            ssBean.setSessionError(new ErrorInformation(PwmError.ERROR_NO_CHALLENGES));
            LOGGER.debug(pwmSession, "no challenge sets configured for user " + uiBean.getUserDN());
            Helper.forwardToErrorPage(req, resp, this.getServletContext());
            return;
        }

        populateBean(pwmSession, assignedCs);

        // handle the requested action.
        if ("validateResponses".equalsIgnoreCase(processRequestParam)) {
            handleValidateResponses(req, resp, pwmSession, assignedCs);
            return;
        } else if ("setResponses".equalsIgnoreCase(processRequestParam)) {
            handleSetupResponses(req, resp, pwmSession, assignedCs);
            return;
        } else if ("confirmResponses".equalsIgnoreCase(processRequestParam)) {
            handleConfirmResponses(req, resp, pwmSession);
            return;
        } else if ("changeResponses".equalsIgnoreCase(processRequestParam)) {
            this.forwardToJSP(req, resp);
            return;
        }

        this.forwardToJSP(req, resp);
    }

    /**
     * Handle requests for ajax feedback of user supplied responses.
     *
     * @param req HttpRequest
     * @param resp HttpResponse
     * @param pwmSession An instance of the pwm session
     * @param challengeSet Assigned challenges
     * @throws IOException for an IO error
     * @throws ServletException for an http servlet error
     * @throws PwmException for any unexpected error
     * @throws ChaiUnavailableException if the ldap directory becomes unavailable
     */
    protected static void handleValidateResponses(
            final HttpServletRequest req,
            final HttpServletResponse resp,
            final PwmSession pwmSession,
            final ChallengeSet challengeSet
    )
            throws IOException, ServletException, PwmException, ChaiUnavailableException
    {
        Validator.validatePwmFormID(req);

        boolean success = true;        
        String userMessage = Message.getLocalizedMessage(pwmSession.getSessionStateBean().getLocale(), Message.SUCCESS_RESPONSES_MEET_RULES);

        try {
            // read in the responses from the request
            final Map<Challenge, String> responseMap = readResponsesFromJsonRequest(req, pwmSession, challengeSet);
            validateResponses(pwmSession, challengeSet, responseMap);
            generateResponseSet(pwmSession, challengeSet, responseMap);
        } catch (SetupResponsesException e) {
            success = false;
            userMessage = e.getErrorInformation().toUserStr(pwmSession);
        }

        final Map<String,String> outputMap = new HashMap<String,String>();
        outputMap.put("version","1");
        outputMap.put("message", userMessage);
        outputMap.put("success", String.valueOf(success));
        final String output = JSONObject.toJSONString(outputMap);

        resp.setContentType("text/plain;charset=utf-8");
        resp.getWriter().print(output);

        LOGGER.trace(pwmSession,"ajax validate responses: " + output);
    }

    private void handleSetupResponses(
            final HttpServletRequest req,
            final HttpServletResponse resp,
            final PwmSession pwmSession,
            final ChallengeSet challengeSet
    )
            throws PwmException, IOException, ServletException, ChaiUnavailableException
    {
        final SessionStateBean ssBean = pwmSession.getSessionStateBean();

        Validator.validatePwmFormID(req);

        final ResponseSet responses;
        final Map<Challenge,String> responseMap;
        try {
            // build a response set based on the user's challenge set and the html form response.
            responseMap = readResponsesFromHttpRequest(req, pwmSession, challengeSet);
            validateResponses(pwmSession, challengeSet, responseMap);
            responses = generateResponseSet(pwmSession, challengeSet, responseMap);

        } catch (SetupResponsesException e) {
            LOGGER.debug(pwmSession, "error with user's supplied new responses: " + e.getErrorInformation().toDebugStr());
            ssBean.setSessionError(e.getErrorInformation());
            this.forwardToJSP(req, resp);
            return;
        }

        LOGGER.trace(pwmSession, "user's supplied new responses appear to be acceptable");

        if (pwmSession.getConfig().readSettingAsBoolean(PwmSetting.CHALLENGE_SHOW_CONFIRMATION)) {
            pwmSession.getSetupResponseBean().setResponseMap(responseMap);
            this.forwardToConfirmJSP(req,resp);
        } else {
            final boolean saveSuccess = saveResponses(pwmSession, responses);
            if (saveSuccess) {
                Helper.forwardToSuccessPage(req, resp, this.getServletContext());
            } else {
                this.forwardToJSP(req,resp);
            }
        }
    }

    private void handleConfirmResponses(
            final HttpServletRequest req,
            final HttpServletResponse resp,
            final PwmSession pwmSession
    )
            throws PwmException, IOException, ServletException, ChaiUnavailableException
    {
        boolean saveSuccess = false;

        Validator.validatePwmFormID(req);

        final Map<Challenge,String> responseMap = pwmSession.getSetupResponseBean().getResponseMap();
        if (responseMap != null && !responseMap.isEmpty()) {
            try {
                final ChallengeSet challengeSet = pwmSession.getUserInfoBean().getChallengeSet();
                validateResponses(pwmSession, challengeSet, responseMap);
                final ResponseSet responses = generateResponseSet(pwmSession, challengeSet, responseMap);
                saveSuccess = saveResponses(pwmSession, responses);
            } catch (SetupResponsesException e) {
                LOGGER.debug(pwmSession, "error with user's supplied new responses: " + e.getErrorInformation().toDebugStr());
                pwmSession.getSessionStateBean().setSessionError(e.getErrorInformation());
                this.forwardToJSP(req, resp);
                return;
            }
        }

        if (saveSuccess) {
            Helper.forwardToSuccessPage(req, resp, this.getServletContext());
        } else {
            this.forwardToConfirmJSP(req,resp);
        }
    }

    private static boolean saveResponses(final PwmSession pwmSession, final ResponseSet responses)
            throws PwmException, ChaiUnavailableException
    {
        int attempts = 0, successes = 0;

        try {
            attempts++;
            final boolean storeUsingHash = pwmSession.getConfig().readSettingAsBoolean(PwmSetting.CHALLENGE_STORAGE_HASHED);
            final CrMode writeMode = storeUsingHash ? CrMode.CHAI_SHA1_SALT : CrMode.CHAI_TEXT;
            responses.write(writeMode);
            LOGGER.info(pwmSession, "saved responses for user using method " + writeMode);
            successes++;
        } catch (ChaiOperationException e) {
            if (e.getErrorCode() == ChaiErrorCode.NO_ACCESS) {
                LOGGER.warn(pwmSession,"error writing user's supplied new responses to ldap: " + e.getMessage());
                LOGGER.warn(pwmSession,"user '" + pwmSession.getUserInfoBean().getUserDN() + "' does not appear to have enough rights to save responses");
            } else {
                LOGGER.debug(pwmSession,"error writing user's supplied new responses to ldap: " + e.getMessage());
            }
            pwmSession.getSessionStateBean().setSessionError(new ErrorInformation(PwmError.ERROR_UNKNOWN, e.getMessage()));
        }

        if (pwmSession.getConfig().readSettingAsBoolean(PwmSetting.EDIRECTORY_STORE_NMAS_RESPONSES)) {
            try {
                if (pwmSession.getContextManager().getProxyChaiProvider().getDirectoryVendor() == ChaiProvider.DIRECTORY_VENDOR.NOVELL_EDIRECTORY) {
                    attempts++;
                    responses.write(CrMode.NMAS);
                    LOGGER.info(pwmSession, "saved responses for user using method " + CrMode.NMAS);
                    successes++;
                }
            } catch (ChaiOperationException e) {
                LOGGER.debug(pwmSession,"error writing user's supplied new responses to nmas: " + e.getMessage());
                pwmSession.getSessionStateBean().setSessionError(new ErrorInformation(PwmError.ERROR_UNKNOWN, e.getMessage()));
            }
        }

        pwmSession.getContextManager().getStatisticsManager().incrementValue(Statistic.SETUP_RESPONSES);
        pwmSession.getUserInfoBean().setRequiresResponseConfig(false);
        pwmSession.getSessionStateBean().setSessionSuccess(Message.SUCCESS_SETUP_RESPONSES);
        UserHistory.updateUserHistory(pwmSession, UserHistory.Record.Event.SET_RESPONSES, null);

        if (attempts == successes) {
            if (attempts == 0) {
                LOGGER.warn(pwmSession, "no response saving methods available or configured");
                return false;
            }
            final UserInfoBean uiBean = pwmSession.getUserInfoBean();
            UserStatusHelper.populateActorUserInfoBean(pwmSession,uiBean.getUserDN(),uiBean.getUserCurrentPassword());
            //pwmSession.getSetupResponseBean().clear();
            return true;
        }

        LOGGER.warn(pwmSession, "response storage only partially successful; attempts=" + attempts + ", successes=" + successes);
        return false;
    }

    private static Map<Challenge,String> readResponsesFromHttpRequest(
            final HttpServletRequest req,
            final PwmSession pwmSession,
            final ChallengeSet challengeSet)
            throws SetupResponsesException, PwmException
    {
        final Map<String,String> inputMap = new HashMap<String,String>();

        for (Enumeration nameEnum = req.getParameterNames(); nameEnum.hasMoreElements(); ) {
            final String paramName = nameEnum.nextElement().toString();
            final String paramValue = Validator.readStringFromRequest(req, paramName, 1024);
            inputMap.put(paramName, paramValue);
        }

        return paramMapToChallengeMap(inputMap, pwmSession, challengeSet);
    }

    private static Map<Challenge,String> readResponsesFromJsonRequest(
            final HttpServletRequest req,
            final PwmSession pwmSession,
            final ChallengeSet challengeSet)
            throws SetupResponsesException, PwmException, IOException {
        final Map<String,String> inputMap = new HashMap<String,String>();

        final String bodyString = Helper.readRequestBody(req, 10 * 1024);
        final JSONObject srcMap = (JSONObject) JSONValue.parse(bodyString);

        for (final Object key : srcMap.keySet()) {
            final String paramName = key.toString();
            final String paramValue = srcMap.get(key).toString();
            inputMap.put(paramName, paramValue);
        }

        return paramMapToChallengeMap(inputMap, pwmSession, challengeSet);
    }

    private static Map<Challenge,String> paramMapToChallengeMap(
            final Map<String,String> inputMap,
            final PwmSession pwmSession,
            final ChallengeSet challengeSet)
            throws SetupResponsesException, PwmException
    {
        final Set<String> problemParams = new HashSet<String>();
        ErrorInformation errorInfo = null;

        { // check for duplicate questions.  need to check the actual req params because the following dupes wont populate duplicates
            final Set<String> questionTexts = new HashSet<String>();
            for (final String paramName : inputMap.keySet()) {
                final String paramValue = inputMap.get(paramName);
                if (paramValue != null && paramValue.length() > 0 && paramName.startsWith(PwmConstants.PARAM_QUESTION_PREFIX)) {
                    if (questionTexts.contains(paramValue.toLowerCase())) {
                        errorInfo = new ErrorInformation(PwmError.ERROR_CHALLENGE_DUPLICATE);
                        problemParams.add(paramName);
                    } else {
                        questionTexts.add(paramValue.toLowerCase());
                    }
                }
            }
        }

        final Map<Challenge, String> readResponses = new LinkedHashMap<Challenge, String>();
        final SetupResponsesBean responsesBean = pwmSession.getSetupResponseBean();

        { // read in the question texts and responses
            for (final String indexKey : responsesBean.getIndexedChallenges().keySet()) {
                final Challenge loopChallenge = responsesBean.getIndexedChallenges().get(indexKey);
                if (loopChallenge.isRequired() || !responsesBean.isSimpleMode()) {

                    if (!loopChallenge.isAdminDefined()) {
                        final String questionText = inputMap.get(PwmConstants.PARAM_QUESTION_PREFIX + indexKey);
                        loopChallenge.setChallengeText(questionText);
                    }

                    final String answer = inputMap.get(PwmConstants.PARAM_RESPONSE_PREFIX + indexKey);

                    if (answer.length() > 0) {
                        readResponses.put(loopChallenge, answer);
                    }
                }
            }

            if (responsesBean.isSimpleMode()) { // if in simple mode, read the select-based random challenges
                for (int i = 0; i < challengeSet.getMinRandomRequired(); i++ ) {
                    final String questionKey = inputMap.get(PwmConstants.PARAM_QUESTION_PREFIX + "Random_" + String.valueOf(i));
                    if (questionKey != null && responsesBean.getIndexedChallenges().containsKey(questionKey)) {
                        final Challenge challenge = responsesBean.getIndexedChallenges().get(questionKey);
                        final String answer = inputMap.get(PwmConstants.PARAM_RESPONSE_PREFIX + "Random_" + String.valueOf(i));
                        if (answer != null && answer.length() > 0) {
                            readResponses.put(challenge, answer);
                        }
                    }
                }
            }
        }

        if (errorInfo == null && problemParams.isEmpty()) {
            return readResponses;
        }

        throw new SetupResponsesException(errorInfo,problemParams.toArray(new String[problemParams.size()]));
    }

    private static void validateResponses(
            final PwmSession pwmSession,
            final ChallengeSet challengeSet,
            final Map<Challenge,String> responseMap
    )
            throws SetupResponsesException {

        final int minRandomRequiredSetup = pwmSession.getSetupResponseBean().getMinRandomSetup();

        int randomCount = 0;
        for (final Challenge loopChallenge : responseMap.keySet()) {
            if (!loopChallenge.isRequired()) {
                randomCount++;
            }
        }

        if (minRandomRequiredSetup == 0) { // if using recover style, then all readResponses must be supplied at this point.
            if (randomCount < challengeSet.getRandomChallenges().size()) {
                LOGGER.debug(pwmSession, "all randoms required, but not all randoms are completed");
                final ErrorInformation errorInfo = new ErrorInformation(PwmError.ERROR_MISSING_RANDOM_RESPONSE);
                throw new SetupResponsesException(errorInfo);
            }
        }

        if (randomCount < minRandomRequiredSetup) {
            LOGGER.debug(pwmSession, minRandomRequiredSetup + " randoms required, but not only " + randomCount + " randoms are completed");
            final ErrorInformation errorInfo = new ErrorInformation(PwmError.ERROR_MISSING_RANDOM_RESPONSE);
            throw new SetupResponsesException(errorInfo);
        }

        final boolean applyWordlist = pwmSession.getContextManager().getConfig().readSettingAsBoolean(PwmSetting.CHALLENGE_APPLY_WORDLIST);
        final WordlistManager wordlistManager = pwmSession.getContextManager().getWordlistManager();

        if (applyWordlist && wordlistManager.getStatus().isAvailable()) {
            for (final Challenge loopChallenge : responseMap.keySet()) {
                final String answer = responseMap.get(loopChallenge);
                if (wordlistManager.containsWord(pwmSession, answer)) {
                    final ErrorInformation errorInfo = new ErrorInformation(PwmError.ERROR_RESPONSE_WORDLIST,null,loopChallenge.getChallengeText());
                    throw new SetupResponsesException(errorInfo);
                }
            }
        }
    }

    private static ResponseSet generateResponseSet(
            final PwmSession pwmSession,
            final ChallengeSet challengeSet,
            final Map<Challenge,String> readResponses
    )
            throws ChaiUnavailableException, SetupResponsesException, PwmException
    {
        final ChaiProvider provider = pwmSession.getSessionManager().getChaiProvider();
        final ChaiUser actor = ChaiFactory.createChaiUser(pwmSession.getUserInfoBean().getUserDN(), provider);

        try {
            final ResponseSet responseSet = CrFactory.newResponseSet(
                    readResponses,
                    challengeSet.getLocale(),
                    challengeSet.getMinRandomRequired(),
                    actor,
                    challengeSet.getIdentifier()
            );

            responseSet.meetsChallengeSetRequirements(challengeSet);

            final int minRandomRequiredSetup = pwmSession.getSetupResponseBean().getMinRandomSetup();
            if (minRandomRequiredSetup == 0) { // if using recover style, then all readResponses must be supplied at this point.
                if (responseSet.getChallengeSet().getRandomChallenges().size() < challengeSet.getRandomChallenges().size()) {
                    throw new ChaiValidationException(ChaiValidationException.VALIDATION_ERROR.TOO_FEW_RANDOM_RESPONSES);
                }
            }

            return responseSet;
        } catch (ChaiValidationException e) {
            final ErrorInformation errorInfo = convertChaiValidationException(e);
            throw new SetupResponsesException(errorInfo);
        }
    }

    private void forwardToJSP(
            final HttpServletRequest req,
            final HttpServletResponse resp
    )
            throws IOException, ServletException
    {
        this.getServletContext().getRequestDispatcher('/' + PwmConstants.URL_JSP_SETUP_RESPONSES).forward(req, resp);
    }

    private void forwardToConfirmJSP(
            final HttpServletRequest req,
            final HttpServletResponse resp
    )
            throws IOException, ServletException
    {
        this.getServletContext().getRequestDispatcher('/' + PwmConstants.URL_JSP_CONFIRM_RESPONSES).forward(req, resp);
    }

    private static ErrorInformation convertChaiValidationException(
            final ChaiValidationException e
    )
    {
        switch (e.getValidationError()) {
            case TOO_FEW_CHALLENGES:
                return new ErrorInformation(PwmError.ERROR_MISSING_REQUIRED_RESPONSE, null, e.getValidationField());

            case TOO_FEW_RANDOM_RESPONSES:
                return new ErrorInformation(PwmError.ERROR_MISSING_RANDOM_RESPONSE, null, e.getValidationField());

            case MISSING_REQUIRED_CHALLENGE_TEXT:
                return new ErrorInformation(PwmError.ERROR_MISSING_CHALLENGE_TEXT, null, e.getValidationField());

            case RESPONSE_TOO_LONG:
                return new ErrorInformation(PwmError.ERROR_RESPONSE_TOO_LONG, null, e.getValidationField());

            case RESPONSE_TOO_SHORT:
            case MISSING_REQUIRED_RESPONSE_TEXT:
                return new ErrorInformation(PwmError.ERROR_RESPONSE_TOO_SHORT, null, e.getValidationField());

            case DUPLICATE_RESPONSES:
                return new ErrorInformation(PwmError.ERROR_RESPONSE_DUPLICATE, null, e.getValidationField());

            default:
                return new ErrorInformation(PwmError.ERROR_UNKNOWN);
        }
    }

    private void populateBean(final PwmSession pwmSession, final ChallengeSet challengeSet) {
        int minRandomSetup;
        boolean useSimple = true;
        final Map<String, Challenge> indexedChallenges = new LinkedHashMap<String, Challenge>();

        {
            minRandomSetup = pwmSession.getConfig().readSettingAsInt(PwmSetting.CHALLENGE_MIN_RANDOM_SETUP);
            if (minRandomSetup != 0 && minRandomSetup < challengeSet.getMinRandomRequired()) {
                minRandomSetup = challengeSet.getMinRandomRequired();
            }
            if (minRandomSetup > challengeSet.getRandomChallenges().size()) {
                minRandomSetup = 0;
            }
        }
        {
            {
                if (minRandomSetup == 0) {
                    useSimple = false;
                }

                for (final Challenge challenge : challengeSet.getChallenges()) {
                    if (!challenge.isRequired() && !challenge.isAdminDefined()) {
                        useSimple = false;
                    }
                }

                if (challengeSet.getRandomChallenges().size() == challengeSet.getMinRandomRequired()) {
                    useSimple = false;
                }
            }
        }

        {
            int i = 0;
            for (final Challenge loopChallenge : challengeSet.getChallenges()) {
                indexedChallenges.put(String.valueOf(i),loopChallenge);
                i++;
            }
        }

        pwmSession.getSetupResponseBean().setSimpleMode(useSimple);
        pwmSession.getSetupResponseBean().setChallengeList(indexedChallenges);
        pwmSession.getSetupResponseBean().setMinRandomSetup(minRandomSetup);
    }

    private static class SetupResponsesException extends Exception {
        private final ErrorInformation errorInformation;
        private final Set<String> problemParams = new HashSet<String>();


        SetupResponsesException(final ErrorInformation errorInformation, final String... problemParams) {
            this.errorInformation = errorInformation;
            if (problemParams != null) {
                this.problemParams.addAll(Arrays.asList(problemParams));
            }
        }

        public ErrorInformation getErrorInformation() {
            return errorInformation;
        }

        public Set<String> getProblemParams() {
            return problemParams;
        }
    }
}

