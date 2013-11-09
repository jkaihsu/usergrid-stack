/*******************************************************************************
 * Copyright 2012 Apigee Corporation
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.usergrid.security.shiro;

import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.usergrid.management.AccountCreationProps.PROPERTIES_SYSADMIN_LOGIN_ALLOWED;
import static org.usergrid.persistence.cassandra.CassandraService.MANAGEMENT_APPLICATION_ID;
import static org.usergrid.security.shiro.utils.SubjectUtils.getPermissionFromPath;
import static org.usergrid.utils.StringUtils.stringOrSubstringAfterFirst;
import static org.usergrid.utils.StringUtils.stringOrSubstringBeforeFirst;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang.StringUtils;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.CredentialsException;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.credential.AllowAllCredentialsMatcher;
import org.apache.shiro.authc.credential.CredentialsMatcher;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.authz.permission.PermissionResolver;
import org.apache.shiro.cache.CacheManager;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.session.Session;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.usergrid.management.AccountCreationProps;
import org.usergrid.management.ApplicationInfo;
import org.usergrid.management.ManagementService;
import org.usergrid.management.OrganizationInfo;
import org.usergrid.management.UserInfo;
import org.usergrid.persistence.Entity;
import org.usergrid.persistence.EntityManager;
import org.usergrid.persistence.EntityManagerFactory;
import org.usergrid.persistence.Results;
import org.usergrid.persistence.Results.Level;
import org.usergrid.persistence.SimpleEntityRef;
import org.usergrid.persistence.entities.Group;
import org.usergrid.persistence.entities.Role;
import org.usergrid.persistence.entities.User;
import org.usergrid.security.shiro.auth.UsergridAuthorizationInfo;
import org.usergrid.security.shiro.credentials.AccessTokenCredentials;
import org.usergrid.security.shiro.credentials.AdminUserAccessToken;
import org.usergrid.security.shiro.credentials.AdminUserPassword;
import org.usergrid.security.shiro.credentials.ApplicationAccessToken;
import org.usergrid.security.shiro.credentials.ApplicationUserAccessToken;
import org.usergrid.security.shiro.credentials.ClientCredentials;
import org.usergrid.security.shiro.credentials.OrganizationAccessToken;
import org.usergrid.security.shiro.credentials.PrincipalCredentials;
import org.usergrid.security.shiro.principals.AdminUserPrincipal;
import org.usergrid.security.shiro.principals.ApplicationGuestPrincipal;
import org.usergrid.security.shiro.principals.ApplicationPrincipal;
import org.usergrid.security.shiro.principals.ApplicationUserPrincipal;
import org.usergrid.security.shiro.principals.OrganizationPrincipal;
import org.usergrid.security.shiro.principals.PrincipalIdentifier;
import org.usergrid.security.tokens.TokenInfo;
import org.usergrid.security.tokens.TokenService;

import com.google.common.collect.HashBiMap;

public class Realm extends AuthorizingRealm {
    private static final Logger logger = LoggerFactory.getLogger(Realm.class);

    public final static String ROLE_SERVICE_ADMIN = "service-admin";
    public final static String ROLE_ADMIN_USER = "admin-user";
    public final static String ROLE_ORGANIZATION_ADMIN = "organization-admin";
    public final static String ROLE_APPLICATION_ADMIN = "application-admin";
    public final static String ROLE_APPLICATION_USER = "application-user";

    private EntityManagerFactory emf;
    private ManagementService management;
    private TokenService tokens;

    
    @Value("${"+PROPERTIES_SYSADMIN_LOGIN_ALLOWED+"}")
    private boolean superUserEnabled;
    @Value("${"+AccountCreationProps.PROPERTIES_SYSADMIN_LOGIN_NAME+":admin}")
    private String superUser;

    public Realm() {
        setCredentialsMatcher(new AllowAllCredentialsMatcher());
        setPermissionResolver(new CustomPermissionResolver());
    }

    public Realm(CacheManager cacheManager) {
        super(cacheManager);
        setCredentialsMatcher(new AllowAllCredentialsMatcher());
        setPermissionResolver(new CustomPermissionResolver());
    }

    public Realm(CredentialsMatcher matcher) {
        super(new AllowAllCredentialsMatcher());
        setPermissionResolver(new CustomPermissionResolver());
    }

    public Realm(CacheManager cacheManager, CredentialsMatcher matcher) {
        super(cacheManager, new AllowAllCredentialsMatcher());
        setPermissionResolver(new CustomPermissionResolver());
    }

    @Override
    public void setCredentialsMatcher(CredentialsMatcher credentialsMatcher) {
        if (!(credentialsMatcher instanceof AllowAllCredentialsMatcher)) {
            logger.debug("Replacing {} with AllowAllCredentialsMatcher", credentialsMatcher);
            credentialsMatcher = new AllowAllCredentialsMatcher();
        }
        super.setCredentialsMatcher(credentialsMatcher);
    }

    @Override
    public void setPermissionResolver(PermissionResolver permissionResolver) {
        if (!(permissionResolver instanceof CustomPermissionResolver)) {
            logger.debug("Replacing {} with AllowAllCredentialsMatcher", permissionResolver);
            permissionResolver = new CustomPermissionResolver();
        }
        super.setPermissionResolver(permissionResolver);
    }


    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(
            AuthenticationToken token) throws AuthenticationException {
        PrincipalCredentialsToken pcToken = (PrincipalCredentialsToken) token;

        if (pcToken.getCredentials() == null) {
            throw new CredentialsException("Missing credentials");
        }

        boolean authenticated = false;

        PrincipalIdentifier principal = pcToken.getPrincipal();
        PrincipalCredentials credentials = pcToken.getCredentials();

        if (credentials instanceof ClientCredentials) {
            authenticated = true;
        } else if ((principal instanceof AdminUserPrincipal)
                && (credentials instanceof AdminUserPassword)) {
            authenticated = true;
        } else if ((principal instanceof AdminUserPrincipal)
                && (credentials instanceof AdminUserAccessToken)) {
            authenticated = true;
        } else if ((principal instanceof ApplicationUserPrincipal)
                && (credentials instanceof ApplicationUserAccessToken)) {
            authenticated = true;
        } else if ((principal instanceof ApplicationPrincipal)
                && (credentials instanceof ApplicationAccessToken)) {
            authenticated = true;
        } else if ((principal instanceof OrganizationPrincipal)
                && (credentials instanceof OrganizationAccessToken)) {
            authenticated = true;
        }

        if (principal != null) {
            if (!principal.isActivated()) {
                throw new AuthenticationException("Unactivated identity");
            }
            if (principal.isDisabled()) {
                throw new AuthenticationException("Disabled identity");
            }
        }

        if (!authenticated) {
            throw new AuthenticationException("Unable to authenticate");
        }
        
        logger.debug("Authenticated: {}",  principal);

        SimpleAuthenticationInfo info = new SimpleAuthenticationInfo(
                pcToken.getPrincipal(), pcToken.getCredentials(), getName());
        return info;
    }

    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(
            PrincipalCollection principals) {
        UsergridAuthorizationInfo info = new UsergridAuthorizationInfo();


        for (PrincipalIdentifier principal : principals
                .byType(PrincipalIdentifier.class)) {

          try {
            principal.populateAuthorizatioInfo(info, this);
          } catch (Exception e) {
            logger.error("Unable to populate authorization info");
            throw new RuntimeException("Unable to populate authorization info", e);
          }

        }

        return info;
    }


    @Override
    public boolean supports(AuthenticationToken token) {
        return token instanceof PrincipalCredentialsToken;
    }


  @Autowired
  public void setEntityManagerFactory(EntityManagerFactory emf) {
    this.emf = emf;
  }

  @Autowired
  public void setManagementService(ManagementService management) {
    this.management = management;
  }

  @Autowired
  public void setTokenService(TokenService tokens) {
    this.tokens = tokens;
  }

  public EntityManagerFactory getEmf() {
    return emf;
  }

  public ManagementService getManagement() {
    return management;
  }

  public TokenService getTokens() {
    return tokens;
  }

  public boolean isSuperUserEnabled() {
    return superUserEnabled;
  }

  public String getSuperUser() {
    return superUser;
  }
}
