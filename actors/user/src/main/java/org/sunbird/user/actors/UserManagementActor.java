package org.sunbird.user.actors;

import akka.actor.ActorRef;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.actor.core.BaseActor;
import org.sunbird.actor.router.ActorConfig;
import org.sunbird.actorutil.InterServiceCommunication;
import org.sunbird.actorutil.InterServiceCommunicationFactory;
import org.sunbird.actorutil.location.impl.LocationClientImpl;
import org.sunbird.actorutil.org.OrganisationClient;
import org.sunbird.actorutil.org.impl.OrganisationClientImpl;
import org.sunbird.actorutil.systemsettings.SystemSettingClient;
import org.sunbird.actorutil.systemsettings.impl.SystemSettingClientImpl;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.common.exception.ProjectCommonException;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.util.ActorOperations;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.LocationActorOperation;
import org.sunbird.common.models.util.LoggerEnum;
import org.sunbird.common.models.util.ProjectLogger;
import org.sunbird.common.models.util.ProjectUtil;
import org.sunbird.common.models.util.StringFormatter;
import org.sunbird.common.models.util.TelemetryEnvKey;
import org.sunbird.common.request.ExecutionContext;
import org.sunbird.common.request.Request;
import org.sunbird.common.request.UserRequestValidator;
import org.sunbird.common.responsecode.ResponseCode;
import org.sunbird.common.responsecode.ResponseMessage;
import org.sunbird.common.util.Matcher;
import org.sunbird.content.store.util.ContentStoreUtil;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.learner.actors.role.service.RoleService;
import org.sunbird.learner.organisation.external.identity.service.OrgExternalService;
import org.sunbird.learner.util.DataCacheHandler;
import org.sunbird.learner.util.UserFlagUtil;
import org.sunbird.learner.util.Util;
import org.sunbird.models.organisation.Organisation;
import org.sunbird.models.user.User;
import org.sunbird.models.user.UserType;
import org.sunbird.models.user.org.UserOrg;
import org.sunbird.telemetry.util.TelemetryUtil;
import org.sunbird.user.dao.UserOrgDao;
import org.sunbird.user.dao.impl.UserOrgDaoImpl;
import org.sunbird.user.service.UserService;
import org.sunbird.user.service.impl.UserServiceImpl;
import org.sunbird.user.util.UserActorOperations;
import org.sunbird.user.util.UserUtil;

@ActorConfig(
  tasks = {"createUser", "updateUser"},
  asyncTasks = {}
)
public class UserManagementActor extends BaseActor {
  private ObjectMapper mapper = new ObjectMapper();
  private CassandraOperation cassandraOperation = ServiceFactory.getInstance();
  private static final boolean IS_REGISTRY_ENABLED =
      Boolean.parseBoolean(ProjectUtil.getConfigValue(JsonKey.SUNBIRD_OPENSABER_BRIDGE_ENABLE));
  private UserRequestValidator userRequestValidator = new UserRequestValidator();
  private UserService userService = UserServiceImpl.getInstance();
  private SystemSettingClient systemSettingClient = SystemSettingClientImpl.getInstance();
  private OrganisationClient organisationClient = new OrganisationClientImpl();
  private OrgExternalService orgExternalService = new OrgExternalService();
  private Util.DbInfo usrDbInfo = Util.dbInfoMap.get(JsonKey.USER_DB);
  private static InterServiceCommunication interServiceCommunication =
      InterServiceCommunicationFactory.getInstance();
  private ActorRef systemSettingActorRef = null;

  @Override
  public void onReceive(Request request) throws Throwable {
    Util.initializeContext(request, TelemetryEnvKey.USER);
    ExecutionContext.setRequestId(request.getRequestId());
    cacheFrameworkFieldsConfig();
    if (systemSettingActorRef == null) {
      systemSettingActorRef = getActorRef(ActorOperations.GET_SYSTEM_SETTING.getValue());
    }
    String operation = request.getOperation();
    switch (operation) {
      case "createUser":
        createUser(request);
        break;
      case "updateUser":
        updateUser(request);
        break;
      default:
        onReceiveUnsupportedOperation("UserManagementActor");
    }
  }

  private void cacheFrameworkFieldsConfig() {
    if (MapUtils.isEmpty(DataCacheHandler.getFrameworkFieldsConfig())) {
      Map<String, List<String>> frameworkFieldsConfig =
          systemSettingClient.getSystemSettingByFieldAndKey(
              getActorRef(ActorOperations.GET_SYSTEM_SETTING.getValue()),
              JsonKey.USER_PROFILE_CONFIG,
              JsonKey.FRAMEWORK,
              new TypeReference<Map<String, List<String>>>() {});
      DataCacheHandler.setFrameworkFieldsConfig(frameworkFieldsConfig);
    }
  }

  @SuppressWarnings("unchecked")
  private void updateUser(Request actorMessage) {
    Map<String, Object> targetObject = null;
    List<Map<String, Object>> correlatedObject = new ArrayList<>();
    actorMessage.toLower();
    Util.getUserProfileConfig(systemSettingActorRef);
    String callerId = (String) actorMessage.getContext().get(JsonKey.CALLER_ID);
    boolean isPrivate = false;
    if (actorMessage.getContext().containsKey(JsonKey.PRIVATE)) {
      isPrivate = (boolean) actorMessage.getContext().get(JsonKey.PRIVATE);
    }
    if (!isPrivate) {
      if (StringUtils.isNotBlank(callerId)) {
        userService.validateUploader(actorMessage);
      } else {
        userService.validateUserId(actorMessage);
      }
    }
    Map<String, Object> userMap = actorMessage.getRequest();
    userRequestValidator.validateUpdateUserRequest(actorMessage);
    validateUserOrganisations(actorMessage, isPrivate);
    Map<String, Object> userDbRecord = UserUtil.validateExternalIdsAndReturnActiveUser(userMap);
    validateUserFrameworkData(userMap, userDbRecord);
    validateUserTypeForUpdate(userMap);
    User user = mapper.convertValue(userMap, User.class);
    UserUtil.validateExternalIds(user, JsonKey.UPDATE);
    userMap.put(JsonKey.EXTERNAL_IDS, user.getExternalIds());
    UserUtil.validateUserPhoneEmailAndWebPages(user, JsonKey.UPDATE);
    // not allowing user to update the status,provider,userName
    removeFieldsFrmReq(userMap);
    // if we are updating email then need to update isEmailVerified flag inside keycloak
    UserUtil.checkEmailSameOrDiff(userMap, userDbRecord);
    convertValidatedLocationCodesToIDs(userMap);

    userMap.put(JsonKey.UPDATED_DATE, ProjectUtil.getFormattedDate());
    if (StringUtils.isBlank(callerId)) {
      userMap.put(JsonKey.UPDATED_BY, actorMessage.getContext().get(JsonKey.REQUESTED_BY));
    }
    Map<String, Object> requestMap = UserUtil.encryptUserData(userMap);
    validateRecoveryEmailPhone(userDbRecord, userMap);
    UserUtil.addMaskEmailAndMaskPhone(requestMap);
    removeUnwanted(requestMap);
    if (requestMap.containsKey(JsonKey.TNC_ACCEPTED_ON)) {
      requestMap.put(
          JsonKey.TNC_ACCEPTED_ON, new Timestamp((Long) requestMap.get(JsonKey.TNC_ACCEPTED_ON)));
    }
    if (requestMap.containsKey(JsonKey.RECOVERY_EMAIL)
        && StringUtils.isBlank((String) requestMap.get(JsonKey.RECOVERY_EMAIL))) {
      requestMap.put(JsonKey.RECOVERY_EMAIL, null);
    }
    if (requestMap.containsKey(JsonKey.RECOVERY_PHONE)
        && StringUtils.isBlank((String) requestMap.get(JsonKey.RECOVERY_PHONE))) {
      requestMap.put(JsonKey.RECOVERY_PHONE, null);
    }

    Map<String, Boolean> userBooleanMap = updatedUserFlagsMap(userMap, userDbRecord);
    int userFlagValue = userFlagsToNum(userBooleanMap);
    requestMap.put(JsonKey.FLAGS_VALUE, userFlagValue);
    Response response =
        cassandraOperation.updateRecord(
            usrDbInfo.getKeySpace(), usrDbInfo.getTableName(), requestMap);

    if (StringUtils.isNotBlank(callerId)) {
      userMap.put(JsonKey.ROOT_ORG_ID, actorMessage.getContext().get(JsonKey.ROOT_ORG_ID));
    }
    Response resp = null;
    if (((String) response.get(JsonKey.RESPONSE)).equalsIgnoreCase(JsonKey.SUCCESS)) {
      if (isPrivate) {
        updateUserOrganisations(actorMessage);
      }
      Map<String, Object> userRequest = new HashMap<>(userMap);
      userRequest.put(JsonKey.OPERATION_TYPE, JsonKey.UPDATE);
      resp = saveUserAttributes(userRequest);
    } else {
      ProjectLogger.log("UserManagementActor:updateUser: User update failure");
    }
    response.put(
        JsonKey.ERRORS,
        ((Map<String, Object>) resp.getResult().get(JsonKey.RESPONSE)).get(JsonKey.ERRORS));
    sender().tell(response, self());
    if (null != resp) {
      Map<String, Object> completeUserDetails = new HashMap<>(userDbRecord);
      completeUserDetails.putAll(requestMap);
      saveUserDetailsToEs(completeUserDetails);
    }
    targetObject =
        TelemetryUtil.generateTargetObject(
            (String) userMap.get(JsonKey.USER_ID), TelemetryEnvKey.USER, JsonKey.UPDATE, null);
    TelemetryUtil.telemetryProcessingCall(userMap, targetObject, correlatedObject);
  }

  @SuppressWarnings("unchecked")
  private void validateUserOrganisations(Request actorMessage, boolean isPrivate) {
    if (isPrivate && null != actorMessage.getRequest().get(JsonKey.ORGANISATIONS)) {
      List<Map<String, Object>> userOrgList =
          (List<Map<String, Object>>) actorMessage.getRequest().get(JsonKey.ORGANISATIONS);
      if (CollectionUtils.isNotEmpty(userOrgList)) {
        List<String> orgIdList = new ArrayList<>();
        userOrgList.forEach(org -> orgIdList.add((String) org.get(JsonKey.ORGANISATION_ID)));
        List<String> fields = new ArrayList<>();
        fields.add(JsonKey.HASHTAGID);
        fields.add(JsonKey.ID);
        List<Organisation> orgList = organisationClient.esSearchOrgByIds(orgIdList, fields);
        Map<String, Object> orgMap = new HashMap<>();
        orgList.forEach(org -> orgMap.put(org.getId(), org));
        List<String> missingOrgIds = new ArrayList<>();
        for (Map<String, Object> userOrg : userOrgList) {
          String orgId = (String) userOrg.get(JsonKey.ORGANISATION_ID);
          Organisation organisation = (Organisation) orgMap.get(orgId);
          if (null == organisation) {
            missingOrgIds.add(orgId);
          } else {
            userOrg.put(JsonKey.HASH_TAG_ID, organisation.getHashTagId());
            if (userOrg.get(JsonKey.ROLES) != null) {
              List<String> rolesList = (List<String>) userOrg.get(JsonKey.ROLES);
              RoleService.validateRoles(rolesList);
              if (!rolesList.contains(ProjectUtil.UserRole.PUBLIC.getValue())) {
                rolesList.add(ProjectUtil.UserRole.PUBLIC.getValue());
              }
            } else {
              userOrg.put(JsonKey.ROLES, Arrays.asList(ProjectUtil.UserRole.PUBLIC.getValue()));
            }
          }
        }
        if (!missingOrgIds.isEmpty()) {
          ProjectCommonException.throwClientErrorException(
              ResponseCode.invalidParameterValue,
              MessageFormat.format(
                  ResponseCode.invalidParameterValue.getErrorMessage(),
                  JsonKey.ORGANISATION_ID,
                  missingOrgIds));
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void updateUserOrganisations(Request actorMessage) {
    if (null != actorMessage.getRequest().get(JsonKey.ORGANISATIONS)) {
      ProjectLogger.log("UserManagementActor: updateUserOrganisation called", LoggerEnum.INFO);
      List<Map<String, Object>> orgList =
          (List<Map<String, Object>>) actorMessage.getRequest().get(JsonKey.ORGANISATIONS);
      String userId = (String) actorMessage.getRequest().get(JsonKey.USER_ID);
      String rootOrgId = getUserRootOrgId(userId);
      List<Map<String, Object>> orgListDb = UserUtil.getAllUserOrgDetails(userId);
      Map<String, Object> orgDbMap = new HashMap<>();
      if (CollectionUtils.isNotEmpty(orgListDb)) {
        orgListDb.forEach(org -> orgDbMap.put((String) org.get(JsonKey.ORGANISATION_ID), org));
      }
      if (!orgList.isEmpty()) {
        for (Map<String, Object> org : orgList) {
          createOrUpdateOrganisations(org, orgDbMap, actorMessage);
        }
      }
      String requestedBy = (String) actorMessage.getContext().get(JsonKey.REQUESTED_BY);
      removeOrganisations(orgDbMap, rootOrgId, requestedBy);
      ProjectLogger.log(
          "UserManagementActor:updateUserOrganisations : " + "updateUserOrganisation Completed",
          LoggerEnum.INFO);
    }
  }

  private String getUserRootOrgId(String userId) {
    User user = userService.getUserById(userId);
    return user.getRootOrgId();
  }

  @SuppressWarnings("unchecked")
  private void createOrUpdateOrganisations(
      Map<String, Object> org, Map<String, Object> orgDbMap, Request actorMessage) {
    UserOrgDao userOrgDao = UserOrgDaoImpl.getInstance();
    String userId = (String) actorMessage.getRequest().get(JsonKey.USER_ID);
    if (MapUtils.isNotEmpty(org)) {
      UserOrg userOrg = mapper.convertValue(org, UserOrg.class);
      String orgId = (String) org.get(JsonKey.ORGANISATION_ID);
      userOrg.setUserId(userId);
      userOrg.setDeleted(false);
      if (null != orgId && orgDbMap.containsKey(orgId)) {
        userOrg.setUpdatedDate(ProjectUtil.getFormattedDate());
        userOrg.setUpdatedBy((String) (actorMessage.getContext().get(JsonKey.REQUESTED_BY)));
        userOrg.setId((String) ((Map<String, Object>) orgDbMap.get(orgId)).get(JsonKey.ID));
        userOrgDao.updateUserOrg(userOrg);
        orgDbMap.remove(orgId);
      } else {
        userOrg.setHashTagId((String) (org.get(JsonKey.HASH_TAG_ID)));
        userOrg.setOrgJoinDate(ProjectUtil.getFormattedDate());
        userOrg.setAddedBy((String) actorMessage.getContext().get(JsonKey.REQUESTED_BY));
        userOrg.setId(ProjectUtil.getUniqueIdFromTimestamp(actorMessage.getEnv()));
        userOrgDao.createUserOrg(userOrg);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void removeOrganisations(
      Map<String, Object> orgDbMap, String rootOrgId, String requestedBy) {
    Set<String> ids = orgDbMap.keySet();
    UserOrgDao userOrgDao = UserOrgDaoImpl.getInstance();
    ids.remove(rootOrgId);
    for (String id : ids) {
      UserOrg userOrg = mapper.convertValue(orgDbMap.get(id), UserOrg.class);
      userOrg.setDeleted(true);
      userOrg.setId((String) ((Map<String, Object>) orgDbMap.get(id)).get(JsonKey.ID));
      userOrg.setUpdatedDate(ProjectUtil.getFormattedDate());
      userOrg.setUpdatedBy(requestedBy);
      userOrg.setOrgLeftDate(ProjectUtil.getFormattedDate());
      userOrgDao.updateUserOrg(userOrg);
    }
  }

  private void validateUserTypeForUpdate(Map<String, Object> userMap) {
    if (userMap.containsKey(JsonKey.USER_TYPE)) {
      String userType = (String) userMap.get(JsonKey.USER_TYPE);
      if (UserType.TEACHER.getTypeName().equalsIgnoreCase(userType)) {
        String custodianChannel = null;
        String custodianRootOrgId = null;
        User user = userService.getUserById((String) userMap.get(JsonKey.USER_ID));
        try {
          custodianRootOrgId = getCustodianRootOrgId();
        } catch (Exception ex) {
          ProjectLogger.log(
              "UserManagementActor: validateUserTypeForUpdate :"
                  + " Exception Occured while fetching Custodian Org ",
              LoggerEnum.INFO);
        }
        if (StringUtils.isNotBlank(custodianRootOrgId)
            && user.getRootOrgId().equalsIgnoreCase(custodianRootOrgId)) {
          ProjectCommonException.throwClientErrorException(
              ResponseCode.errorTeacherCannotBelongToCustodianOrg,
              ResponseCode.errorTeacherCannotBelongToCustodianOrg.getErrorMessage());
        }
      } else {
        userMap.put(JsonKey.USER_TYPE, UserType.OTHER.getTypeName());
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void validateUserFrameworkData(
      Map<String, Object> userRequestMap, Map<String, Object> userDbRecord) {
    if (userRequestMap.containsKey(JsonKey.FRAMEWORK)) {
      Map<String, Object> framework = (Map<String, Object>) userRequestMap.get(JsonKey.FRAMEWORK);
      List<String> frameworkIdList;
      if (framework.get(JsonKey.ID) instanceof String) {
        String frameworkIdString = (String) framework.remove(JsonKey.ID);
        frameworkIdList = new ArrayList<>();
        frameworkIdList.add(frameworkIdString);
        framework.put(JsonKey.ID, frameworkIdList);
      } else {
        frameworkIdList = (List<String>) framework.get(JsonKey.ID);
      }

      userRequestMap.put(JsonKey.FRAMEWORK, framework);
      List<String> frameworkFields =
          DataCacheHandler.getFrameworkFieldsConfig().get(JsonKey.FIELDS);
      List<String> frameworkMandatoryFields =
          DataCacheHandler.getFrameworkFieldsConfig().get(JsonKey.MANDATORY_FIELDS);
      userRequestValidator.validateMandatoryFrameworkFields(
          userRequestMap, frameworkFields, frameworkMandatoryFields);
      Map<String, Object> rootOrgMap =
          Util.getOrgDetails((String) userDbRecord.get(JsonKey.ROOT_ORG_ID));
      String hashtagId = (String) rootOrgMap.get(JsonKey.HASHTAGID);

      verifyFrameworkId(hashtagId, frameworkIdList);
      Map<String, List<Map<String, String>>> frameworkCachedValue =
          getFrameworkDetails(frameworkIdList.get(0));
      ((Map<String, Object>) userRequestMap.get(JsonKey.FRAMEWORK)).remove(JsonKey.ID);
      userRequestValidator.validateFrameworkCategoryValues(userRequestMap, frameworkCachedValue);
      ((Map<String, Object>) userRequestMap.get(JsonKey.FRAMEWORK))
          .put(JsonKey.ID, frameworkIdList);
    }
  }

  private void removeFieldsFrmReq(Map<String, Object> userMap) {
    userMap.remove(JsonKey.ENC_EMAIL);
    userMap.remove(JsonKey.ENC_PHONE);
    userMap.remove(JsonKey.STATUS);
    userMap.remove(JsonKey.PROVIDER);
    userMap.remove(JsonKey.USERNAME);
    userMap.remove(JsonKey.ROOT_ORG_ID);
    userMap.remove(JsonKey.LOGIN_ID);
    userMap.remove(JsonKey.ROLES);
    // channel update is not allowed
    userMap.remove(JsonKey.CHANNEL);
  }

  /**
   * Method to create the new user , Username should be unique .
   *
   * @param actorMessage Request
   */
  private void createUser(Request actorMessage) {
    actorMessage.toLower();
    Map<String, Object> userMap = actorMessage.getRequest();
    String callerId = (String) actorMessage.getContext().get(JsonKey.CALLER_ID);
    String version = (String) actorMessage.getContext().get(JsonKey.VERSION);
    String signupType =
        (String) actorMessage.getContext().get(JsonKey.SIGNUP_TYPE) != null
            ? (String) actorMessage.getContext().get(JsonKey.SIGNUP_TYPE)
            : "";
    String source =
        (String) actorMessage.getContext().get(JsonKey.REQUEST_SOURCE) != null
            ? (String) actorMessage.getContext().get(JsonKey.REQUEST_SOURCE)
            : "";
    if (StringUtils.isNotBlank(version) && JsonKey.VERSION_2.equalsIgnoreCase(version)) {
      userRequestValidator.validateCreateUserV2Request(actorMessage);
      if (StringUtils.isNotBlank(callerId)) {
        userMap.put(JsonKey.ROOT_ORG_ID, actorMessage.getContext().get(JsonKey.ROOT_ORG_ID));
      }
    } else {
      userRequestValidator.validateCreateUserV1Request(actorMessage);
    }
    validateChannelAndOrganisationId(userMap);
    validatePrimaryAndRecoveryKeys(userMap);

    // remove these fields from req
    userMap.remove(JsonKey.ENC_EMAIL);
    userMap.remove(JsonKey.ENC_PHONE);
    actorMessage.getRequest().putAll(userMap);
    Util.getUserProfileConfig(systemSettingActorRef);
    boolean isCustodianOrg = false;
    if (StringUtils.isBlank(callerId)) {
      userMap.put(JsonKey.CREATED_BY, actorMessage.getContext().get(JsonKey.REQUESTED_BY));
      try {
        if (StringUtils.isBlank((String) userMap.get(JsonKey.CHANNEL))
            && StringUtils.isBlank((String) userMap.get(JsonKey.ROOT_ORG_ID))) {
          String channel = userService.getCustodianChannel(userMap, systemSettingActorRef);
          String rootOrgId = userService.getRootOrgIdFromChannel(channel);
          userMap.put(JsonKey.ROOT_ORG_ID, rootOrgId);
          userMap.put(JsonKey.CHANNEL, channel);
          isCustodianOrg = true;
        }
      } catch (Exception ex) {
        sender().tell(ex, self());
        return;
      }
    }
    validateUserType(userMap, isCustodianOrg);
    if (userMap.containsKey(JsonKey.ORG_EXTERNAL_ID)) {
      String orgExternalId = (String) userMap.get(JsonKey.ORG_EXTERNAL_ID);
      String channel = (String) userMap.get(JsonKey.CHANNEL);
      String orgId =
          orgExternalService.getOrgIdFromOrgExternalIdAndProvider(orgExternalId, channel);
      if (StringUtils.isBlank(orgId)) {
        ProjectLogger.log(
            "UserManagementActor:createUser: No organisation with orgExternalId = "
                + orgExternalId
                + " and channel = "
                + channel,
            LoggerEnum.ERROR.name());
        ProjectCommonException.throwClientErrorException(
            ResponseCode.invalidParameterValue,
            MessageFormat.format(
                ResponseCode.invalidParameterValue.getErrorMessage(),
                orgExternalId,
                JsonKey.ORG_EXTERNAL_ID));
      }
      if (userMap.containsKey(JsonKey.ORGANISATION_ID)
          && !orgId.equals(userMap.get(JsonKey.ORGANISATION_ID))) {
        ProjectLogger.log(
            "UserManagementActor:createUser Mismatch of organisation from orgExternalId="
                + orgExternalId
                + " and channel="
                + channel
                + " as organisationId="
                + orgId
                + " and request organisationId="
                + userMap.get(JsonKey.ORGANISATION_ID),
            LoggerEnum.ERROR.name());
        throwParameterMismatchException(JsonKey.ORG_EXTERNAL_ID, JsonKey.ORGANISATION_ID);
      }
      userMap.remove(JsonKey.ORG_EXTERNAL_ID);
      userMap.put(JsonKey.ORGANISATION_ID, orgId);
    }
    processUserRequest(userMap, callerId, signupType, source);
  }

  private void validateUserType(Map<String, Object> userMap, boolean isCustodianOrg) {
    String userType = (String) userMap.get(JsonKey.USER_TYPE);
    if (StringUtils.isNotBlank(userType)) {
      if (userType.equalsIgnoreCase(UserType.TEACHER.getTypeName()) && isCustodianOrg) {
        ProjectCommonException.throwClientErrorException(
            ResponseCode.errorTeacherCannotBelongToCustodianOrg,
            ResponseCode.errorTeacherCannotBelongToCustodianOrg.getErrorMessage());
      } else if (UserType.TEACHER.getTypeName().equalsIgnoreCase(userType)) {
        String custodianRootOrgId = null;
        try {
          custodianRootOrgId = getCustodianRootOrgId();
        } catch (Exception ex) {
          ProjectLogger.log(
              "UserManagementActor: validateUserType :"
                  + " Exception Occured while fetching Custodian Org ",
              LoggerEnum.INFO);
        }
        if (StringUtils.isNotBlank(custodianRootOrgId)
            && ((String) userMap.get(JsonKey.ROOT_ORG_ID)).equalsIgnoreCase(custodianRootOrgId)) {
          ProjectCommonException.throwClientErrorException(
              ResponseCode.errorTeacherCannotBelongToCustodianOrg,
              ResponseCode.errorTeacherCannotBelongToCustodianOrg.getErrorMessage());
        }
      }
    } else {
      userMap.put(JsonKey.USER_TYPE, UserType.OTHER.getTypeName());
    }
  }

  private void validateChannelAndOrganisationId(Map<String, Object> userMap) {
    String organisationId = (String) userMap.get(JsonKey.ORGANISATION_ID);
    String requestedChannel = (String) userMap.get(JsonKey.CHANNEL);

    String subOrgRootOrgId = "";
    if (StringUtils.isNotBlank(organisationId)) {
      Organisation organisation = organisationClient.esGetOrgById(organisationId);
      if (null == organisation) {
        ProjectCommonException.throwClientErrorException(ResponseCode.invalidOrgData);
      }
      if (organisation.isRootOrg()) {
        subOrgRootOrgId = organisation.getId();
        if (StringUtils.isNotBlank(requestedChannel)
            && !requestedChannel.equalsIgnoreCase(organisation.getChannel())) {
          throwParameterMismatchException(JsonKey.CHANNEL, JsonKey.ORGANISATION_ID);
        }
        userMap.put(JsonKey.CHANNEL, organisation.getChannel());
      } else {
        subOrgRootOrgId = organisation.getRootOrgId();
        Organisation subOrgRootOrg = organisationClient.esGetOrgById(subOrgRootOrgId);
        if (null != subOrgRootOrg) {
          if (StringUtils.isNotBlank(requestedChannel)
              && !requestedChannel.equalsIgnoreCase(subOrgRootOrg.getChannel())) {
            throwParameterMismatchException(JsonKey.CHANNEL, JsonKey.ORGANISATION_ID);
          }
          userMap.put(JsonKey.CHANNEL, subOrgRootOrg.getChannel());
        }
      }
      userMap.put(JsonKey.ROOT_ORG_ID, subOrgRootOrgId);
    }
    String rootOrgId = "";
    if (StringUtils.isNotBlank(requestedChannel)) {
      rootOrgId = userService.getRootOrgIdFromChannel(requestedChannel);
      if (StringUtils.isNotBlank(subOrgRootOrgId) && !rootOrgId.equalsIgnoreCase(subOrgRootOrgId)) {
        throwParameterMismatchException(JsonKey.CHANNEL, JsonKey.ORGANISATION_ID);
      }
      userMap.put(JsonKey.ROOT_ORG_ID, rootOrgId);
    }
  }

  private void throwParameterMismatchException(String... param) {
    ProjectCommonException.throwClientErrorException(
        ResponseCode.parameterMismatch,
        MessageFormat.format(
            ResponseCode.parameterMismatch.getErrorMessage(), StringFormatter.joinByComma(param)));
  }

  @SuppressWarnings("unchecked")
  private void processUserRequest(
      Map<String, Object> userMap, String callerId, String signupType, String source) {
    Map<String, Object> requestMap = null;
    UserUtil.setUserDefaultValue(userMap, callerId);
    User user = mapper.convertValue(userMap, User.class);
    UserUtil.validateExternalIds(user, JsonKey.CREATE);
    userMap.put(JsonKey.EXTERNAL_IDS, user.getExternalIds());
    UserUtil.validateUserPhoneEmailAndWebPages(user, JsonKey.CREATE);
    convertValidatedLocationCodesToIDs(userMap);

    UserUtil.toLower(userMap);
    String userId = ProjectUtil.generateUniqueId();
    userMap.put(JsonKey.ID, userId);
    userMap.put(JsonKey.USER_ID, userId);
    requestMap = UserUtil.encryptUserData(userMap);
    UserUtil.addMaskEmailAndMaskPhone(requestMap);
    removeUnwanted(requestMap);
    requestMap.put(JsonKey.IS_DELETED, false);
    Map<String, Boolean> userFlagsMap = new HashMap<>();
    // checks if the user is belongs to state and sets a validation flag
    setStateValidation(requestMap, userFlagsMap);
    userFlagsMap.put(JsonKey.EMAIL_VERIFIED, (Boolean) userMap.get(JsonKey.EMAIL_VERIFIED));
    userFlagsMap.put(JsonKey.PHONE_VERIFIED, (Boolean) userMap.get(JsonKey.PHONE_VERIFIED));
    int userFlagValue = userFlagsToNum(userFlagsMap);
    requestMap.put(JsonKey.FLAGS_VALUE, userFlagValue);
    Response response = null;
    boolean isPasswordUpdated = false;
    try {
      response =
          cassandraOperation.insertRecord(
              usrDbInfo.getKeySpace(), usrDbInfo.getTableName(), requestMap);
      isPasswordUpdated = UserUtil.updatePassword(userMap);

    } finally {
      if (response == null) {
        response = new Response();
      }
      response.put(JsonKey.USER_ID, userMap.get(JsonKey.ID));
      if (!isPasswordUpdated) {
        response.put(JsonKey.ERROR_MSG, ResponseMessage.Message.ERROR_USER_UPDATE_PASSWORD);
      }
    }
    Response resp = null;
    if (((String) response.get(JsonKey.RESPONSE)).equalsIgnoreCase(JsonKey.SUCCESS)) {
      Map<String, Object> userRequest = new HashMap<>();
      userRequest.putAll(userMap);
      userRequest.put(JsonKey.OPERATION_TYPE, JsonKey.CREATE);
      userRequest.put(JsonKey.CALLER_ID, callerId);
      resp = saveUserAttributes(userRequest);
    } else {
      ProjectLogger.log("UserManagementActor:processUserRequest: User creation failure");
    }
    // Enable this when you want to send full response of user attributes
    Map<String, Object> esResponse = new HashMap<>();
    esResponse.putAll((Map<String, Object>) resp.getResult().get(JsonKey.RESPONSE));
    esResponse.putAll(requestMap);
    response.put(
        JsonKey.ERRORS,
        ((Map<String, Object>) resp.getResult().get(JsonKey.RESPONSE)).get(JsonKey.ERRORS));
    sender().tell(response, self());
    if (null != resp) {
      saveUserDetailsToEs(esResponse);
    }
    requestMap.put(JsonKey.PASSWORD, userMap.get(JsonKey.PASSWORD));
    if (StringUtils.isNotBlank(callerId)) {
      sendEmailAndSms(requestMap);
    }
    Map<String, Object> targetObject = null;
    List<Map<String, Object>> correlatedObject = new ArrayList<>();
    Map<String, String> rollUp = new HashMap<>();
    rollUp.put("l1", (String) userMap.get(JsonKey.ROOT_ORG_ID));
    ExecutionContext.getCurrent().getRequestContext().put(JsonKey.ROLLUP, rollUp);
    targetObject =
        TelemetryUtil.generateTargetObject(
            (String) userMap.get(JsonKey.ID), TelemetryEnvKey.USER, JsonKey.CREATE, null);
    TelemetryUtil.generateCorrelatedObject(userId, TelemetryEnvKey.USER, null, correlatedObject);
    if (StringUtils.isNotBlank(signupType)) {
      TelemetryUtil.generateCorrelatedObject(
          signupType, StringUtils.capitalize(JsonKey.SIGNUP_TYPE), null, correlatedObject);
    } else {
      ProjectLogger.log("UserManagementActor:processUserRequest: No signupType found");
    }
    if (StringUtils.isNotBlank(source)) {
      TelemetryUtil.generateCorrelatedObject(
          source, StringUtils.capitalize(JsonKey.REQUEST_SOURCE), null, correlatedObject);
    } else {
      ProjectLogger.log("UserManagementActor:processUserRequest: No source found");
    }

    TelemetryUtil.telemetryProcessingCall(userMap, targetObject, correlatedObject);
  }

  private int userFlagsToNum(Map<String, Boolean> userBooleanMap) {
    int userFlagValue = 0;
    Set<Map.Entry<String, Boolean>> mapEntry = userBooleanMap.entrySet();
    for (Map.Entry<String, Boolean> entry : mapEntry) {
      if (StringUtils.isNotEmpty(entry.getKey())) {
        userFlagValue += UserFlagUtil.getFlagValue(entry.getKey(), entry.getValue());
      }
    }
    return userFlagValue;
  }

  private void setStateValidation(
      Map<String, Object> requestMap, Map<String, Boolean> userBooleanMap) {
    String rootOrgId = (String) requestMap.get(JsonKey.ROOT_ORG_ID);
    String custodianRootOrgId = getCustodianRootOrgId();
    // if the user is creating for non-custodian(i.e state) the value is set as true else false
    userBooleanMap.put(JsonKey.STATE_VALIDATED, !custodianRootOrgId.equals(rootOrgId));
  }

  private Map<String, Boolean> updatedUserFlagsMap(
      Map<String, Object> userMap, Map<String, Object> userDbRecord) {
    Map<String, Boolean> userBooleanMap = new HashMap<>();
    setUserFlagValue(userDbRecord, JsonKey.EMAIL, JsonKey.EMAIL_VERIFIED);
    setUserFlagValue(userDbRecord, JsonKey.PHONE, JsonKey.PHONE_VERIFIED);
    boolean emailVerified =
        (boolean)
            (userMap.containsKey(JsonKey.EMAIL_VERIFIED)
                ? userMap.get(JsonKey.EMAIL_VERIFIED)
                : userDbRecord.get(JsonKey.EMAIL_VERIFIED));
    boolean phoneVerified =
        (boolean)
            (userMap.containsKey(JsonKey.PHONE_VERIFIED)
                ? userMap.get(JsonKey.PHONE_VERIFIED)
                : userDbRecord.get(JsonKey.PHONE_VERIFIED));
    // for existing users, it won't contain state-validation
    // adding in release-2.4.0
    // userDbRecord- record from es.
    if (!userDbRecord.containsKey(JsonKey.STATE_VALIDATED)) {
      setStateValidation(userDbRecord, userBooleanMap);
    } else {
      userBooleanMap.put(
          JsonKey.STATE_VALIDATED, (boolean) userDbRecord.get(JsonKey.STATE_VALIDATED));
    }
    userBooleanMap.put(JsonKey.EMAIL_VERIFIED, emailVerified);
    userBooleanMap.put(JsonKey.PHONE_VERIFIED, phoneVerified);
    return userBooleanMap;
  }


  /** This method set the default value of the user-flag if it is not present in userDbRecord
   * @param userDbRecord
   * @param flagType
   * @param verifiedFlagType
   * @return
   */
  public Map<String, Object> setUserFlagValue(Map<String, Object> userDbRecord, String flagType, String verifiedFlagType) {
    if(userDbRecord.get(flagType) != null && (userDbRecord.get(verifiedFlagType) == null
            || (boolean)userDbRecord.get(verifiedFlagType))) {
      userDbRecord.put(verifiedFlagType, true);
    } else {
      userDbRecord.put(verifiedFlagType, false);
    }
    return userDbRecord;
  }

  private String getCustodianRootOrgId() {
    String custodianChannel =
        userService.getCustodianChannel(new HashMap<>(), systemSettingActorRef);
    return userService.getRootOrgIdFromChannel(custodianChannel);
  }

  @SuppressWarnings("unchecked")
  private void convertValidatedLocationCodesToIDs(Map<String, Object> userMap) {
    if (userMap.containsKey(JsonKey.LOCATION_CODES)
        && !CollectionUtils.isEmpty((List<String>) userMap.get(JsonKey.LOCATION_CODES))) {
      LocationClientImpl locationClient = new LocationClientImpl();
      List<String> locationIdList =
          locationClient.getRelatedLocationIds(
              getActorRef(LocationActorOperation.GET_RELATED_LOCATION_IDS.getValue()),
              (List<String>) userMap.get(JsonKey.LOCATION_CODES));
      if (locationIdList != null && !locationIdList.isEmpty()) {
        userMap.put(JsonKey.LOCATION_IDS, locationIdList);
        userMap.remove(JsonKey.LOCATION_CODES);
      } else {
        ProjectCommonException.throwClientErrorException(
            ResponseCode.invalidParameterValue,
            MessageFormat.format(
                ResponseCode.invalidParameterValue.getErrorMessage(),
                JsonKey.LOCATION_CODES,
                userMap.get(JsonKey.LOCATION_CODES)));
      }
    }
  }

  private void sendEmailAndSms(Map<String, Object> userMap) {
    // sendEmailAndSms
    Request EmailAndSmsRequest = new Request();
    EmailAndSmsRequest.getRequest().putAll(userMap);
    EmailAndSmsRequest.setOperation(UserActorOperations.PROCESS_ONBOARDING_MAIL_AND_SMS.getValue());
    tellToAnother(EmailAndSmsRequest);
  }

  private void saveUserDetailsToEs(Map<String, Object> completeUserMap) {
    Request userRequest = new Request();
    userRequest.setOperation(ActorOperations.UPDATE_USER_INFO_ELASTIC.getValue());
    userRequest.getRequest().put(JsonKey.ID, completeUserMap.get(JsonKey.ID));
    ProjectLogger.log(
        "UserManagementActor:saveUserDetailsToEs: Trigger sync of user details to ES");
    tellToAnother(userRequest);
  }

  private Response saveUserAttributes(Map<String, Object> userMap) {
    Request request = new Request();
    request.setOperation(UserActorOperations.SAVE_USER_ATTRIBUTES.getValue());
    request.getRequest().putAll(userMap);
    ProjectLogger.log("UserManagementActor:saveUserAttributes");
    try {
      return (Response)
          interServiceCommunication.getResponse(
              getActorRef(UserActorOperations.SAVE_USER_ATTRIBUTES.getValue()), request);
    } catch (Exception e) {
      ProjectLogger.log(e.getMessage(), e);
    }
    return null;
  }

  private void removeUnwanted(Map<String, Object> reqMap) {
    reqMap.remove(JsonKey.ADDRESS);
    reqMap.remove(JsonKey.EDUCATION);
    reqMap.remove(JsonKey.JOB_PROFILE);
    reqMap.remove(JsonKey.ORGANISATION);
    reqMap.remove(JsonKey.REGISTERED_ORG);
    reqMap.remove(JsonKey.ROOT_ORG);
    reqMap.remove(JsonKey.IDENTIFIER);
    reqMap.remove(JsonKey.ORGANISATIONS);
    reqMap.remove(JsonKey.IS_DELETED);
    reqMap.remove(JsonKey.EXTERNAL_ID);
    reqMap.remove(JsonKey.ID_TYPE);
    reqMap.remove(JsonKey.EXTERNAL_ID_TYPE);
    reqMap.remove(JsonKey.PROVIDER);
    reqMap.remove(JsonKey.EXTERNAL_ID_PROVIDER);
    reqMap.remove(JsonKey.EXTERNAL_IDS);
    reqMap.remove(JsonKey.ORGANISATION_ID);
  }

  public static void verifyFrameworkId(String hashtagId, List<String> frameworkIdList) {
    List<String> frameworks = DataCacheHandler.getHashtagIdFrameworkIdMap().get(hashtagId);
    String frameworkId = frameworkIdList.get(0);
    if (frameworks != null && frameworks.contains(frameworkId)) {
      return;
    } else {
      Map<String, List<Map<String, String>>> frameworkDetails = getFrameworkDetails(frameworkId);
      if (frameworkDetails == null)
        throw new ProjectCommonException(
            ResponseCode.errorNoFrameworkFound.getErrorCode(),
            ResponseCode.errorNoFrameworkFound.getErrorMessage(),
            ResponseCode.RESOURCE_NOT_FOUND.getResponseCode());
    }
  }

  public static Map<String, List<Map<String, String>>> getFrameworkDetails(String frameworkId) {
    if (DataCacheHandler.getFrameworkCategoriesMap().get(frameworkId) == null) {
      handleGetFrameworkDetails(frameworkId);
    }
    return DataCacheHandler.getFrameworkCategoriesMap().get(frameworkId);
  }

  @SuppressWarnings("unchecked")
  private static void handleGetFrameworkDetails(String frameworkId) {
    Map<String, Object> response = ContentStoreUtil.readFramework(frameworkId);
    Map<String, List<Map<String, String>>> frameworkCacheMap = new HashMap<>();
    List<String> supportedfFields = DataCacheHandler.getFrameworkFieldsConfig().get(JsonKey.FIELDS);
    Map<String, Object> result = (Map<String, Object>) response.get(JsonKey.RESULT);
    if (MapUtils.isNotEmpty(result)) {
      Map<String, Object> frameworkDetails = (Map<String, Object>) result.get(JsonKey.FRAMEWORK);
      if (MapUtils.isNotEmpty(frameworkDetails)) {
        List<Map<String, Object>> frameworkCategories =
            (List<Map<String, Object>>) frameworkDetails.get(JsonKey.CATEGORIES);
        if (CollectionUtils.isNotEmpty(frameworkCategories)) {
          for (Map<String, Object> frameworkCategoriesValue : frameworkCategories) {
            String frameworkField = (String) frameworkCategoriesValue.get(JsonKey.CODE);
            if (supportedfFields.contains(frameworkField)) {
              List<Map<String, String>> listOfFields = new ArrayList<>();
              List<Map<String, Object>> frameworkTermList =
                  (List<Map<String, Object>>) frameworkCategoriesValue.get(JsonKey.TERMS);
              if (CollectionUtils.isNotEmpty(frameworkTermList)) {
                for (Map<String, Object> frameworkTerm : frameworkTermList) {
                  String id = (String) frameworkTerm.get(JsonKey.IDENTIFIER);
                  String name = (String) frameworkTerm.get(JsonKey.NAME);
                  Map<String, String> writtenValue = new HashMap<>();
                  writtenValue.put(JsonKey.ID, id);
                  writtenValue.put(JsonKey.NAME, name);
                  listOfFields.add(writtenValue);
                }
              }
              if (StringUtils.isNotBlank(frameworkField)
                  && CollectionUtils.isNotEmpty(listOfFields))
                frameworkCacheMap.put(frameworkField, listOfFields);
            }
            if (MapUtils.isNotEmpty(frameworkCacheMap))
              DataCacheHandler.updateFrameworkCategoriesMap(frameworkId, frameworkCacheMap);
          }
        }
      }
    }
  }

  private void throwRecoveryParamsMatchException(String type, String recoveryType) {
    ProjectLogger.log(
        "UserManagementActor:throwParamMatchException:".concat(recoveryType + "")
            + "should not same as primary ".concat(type + ""),
        LoggerEnum.ERROR.name());
    ProjectCommonException.throwClientErrorException(
        ResponseCode.recoveryParamsMatchException,
        MessageFormat.format(
            ResponseCode.recoveryParamsMatchException.getErrorMessage(), recoveryType, type));
  }

  private void validateRecoveryEmailPhone(
      Map<String, Object> userDbRecord, Map<String, Object> userReqMap) {
    String userPrimaryPhone = (String) userDbRecord.get(JsonKey.PHONE);
    String userPrimaryEmail = (String) userDbRecord.get(JsonKey.EMAIL);
    String recoveryEmail = (String) userReqMap.get(JsonKey.RECOVERY_EMAIL);
    String recoveryPhone = (String) userReqMap.get(JsonKey.RECOVERY_PHONE);
    if (StringUtils.isNotBlank(recoveryEmail)
        && Matcher.matchIdentifiers(userPrimaryEmail, recoveryEmail)) {
      throwRecoveryParamsMatchException(JsonKey.EMAIL, JsonKey.RECOVERY_EMAIL);
    }
    if (StringUtils.isNotBlank(recoveryPhone)
        && Matcher.matchIdentifiers(userPrimaryPhone, recoveryPhone)) {
      throwRecoveryParamsMatchException(JsonKey.PHONE, JsonKey.RECOVERY_PHONE);
    }
    validatePrimaryEmailOrPhone(userDbRecord, userReqMap);
    validatePrimaryAndRecoveryKeys(userReqMap);
  }

  private void validatePrimaryEmailOrPhone(
      Map<String, Object> userDbRecord, Map<String, Object> userReqMap) {
    String userPrimaryPhone = (String) userReqMap.get(JsonKey.PHONE);
    String userPrimaryEmail = (String) userReqMap.get(JsonKey.EMAIL);
    String recoveryEmail = (String) userDbRecord.get(JsonKey.RECOVERY_EMAIL);
    String recoveryPhone = (String) userDbRecord.get(JsonKey.RECOVERY_PHONE);
    if (StringUtils.isNotBlank(userPrimaryEmail)
        && Matcher.matchIdentifiers(userPrimaryEmail, recoveryEmail)) {
      throwRecoveryParamsMatchException(JsonKey.EMAIL, JsonKey.RECOVERY_EMAIL);
    }
    if (StringUtils.isNotBlank(userPrimaryPhone)
        && Matcher.matchIdentifiers(userPrimaryPhone, recoveryPhone)) {
      throwRecoveryParamsMatchException(JsonKey.PHONE, JsonKey.RECOVERY_PHONE);
    }
  }

  private void validatePrimaryAndRecoveryKeys(Map<String, Object> userReqMap) {
    String userPhone = (String) userReqMap.get(JsonKey.PHONE);
    String userEmail = (String) userReqMap.get(JsonKey.EMAIL);
    String userRecoveryEmail = (String) userReqMap.get(JsonKey.RECOVERY_EMAIL);
    String userRecoveryPhone = (String) userReqMap.get(JsonKey.RECOVERY_PHONE);
    if (StringUtils.isNotBlank(userEmail)
        && Matcher.matchIdentifiers(userEmail, userRecoveryEmail)) {
      throwRecoveryParamsMatchException(JsonKey.EMAIL, JsonKey.RECOVERY_EMAIL);
    }
    if (StringUtils.isNotBlank(userPhone)
        && Matcher.matchIdentifiers(userPhone, userRecoveryPhone)) {
      throwRecoveryParamsMatchException(JsonKey.PHONE, JsonKey.RECOVERY_PHONE);
    }
  }
}