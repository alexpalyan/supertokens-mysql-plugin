/*
 *    Copyright (c) 2021, VRAI Labs and/or its affiliates. All rights reserved.
 *
 *    This software is licensed under the Apache License, Version 2.0 (the
 *    "License") as published by the Apache Software Foundation.
 *
 *    You may not use this file except in compliance with the License. You may
 *    obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package io.supertokens.storage.mysql.queries;

import io.supertokens.pluginInterface.RowMapper;
import io.supertokens.pluginInterface.emailverification.EmailVerificationTokenInfo;
import io.supertokens.pluginInterface.exceptions.StorageQueryException;
import io.supertokens.pluginInterface.exceptions.StorageTransactionLogicException;
import io.supertokens.pluginInterface.multitenancy.AppIdentifier;
import io.supertokens.pluginInterface.multitenancy.TenantIdentifier;
import io.supertokens.storage.mysql.Start;
import io.supertokens.storage.mysql.config.Config;
import io.supertokens.storage.mysql.utils.Utils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static io.supertokens.storage.mysql.QueryExecutorTemplate.execute;
import static io.supertokens.storage.mysql.QueryExecutorTemplate.update;
import static io.supertokens.storage.mysql.config.Config.getConfig;
import static java.lang.System.currentTimeMillis;

public class EmailVerificationQueries {

    static String getQueryToCreateEmailVerificationTable(Start start) {
        return "CREATE TABLE IF NOT EXISTS " + getConfig(start).getEmailVerificationTable() + " ("
                + "app_id VARCHAR(64) DEFAULT 'public',"
                + "user_id VARCHAR(128) NOT NULL,"
                + "email VARCHAR(256) NOT NULL,"
                + "PRIMARY KEY (app_id, user_id, email),"
                + "FOREIGN KEY(app_id)"
                + " REFERENCES " + Config.getConfig(start).getAppsTable() +  " (app_id) ON DELETE CASCADE"
                + ");";
    }

    static String getQueryToCreateEmailVerificationTokensTable(Start start) {
        return "CREATE TABLE IF NOT EXISTS " + getConfig(start).getEmailVerificationTokensTable() + " ("
                + "app_id VARCHAR(64) DEFAULT 'public',"
                + "tenant_id VARCHAR(64) DEFAULT 'public',"
                + "user_id VARCHAR(128) NOT NULL,"
                + "email VARCHAR(256) NOT NULL,"
                + "token VARCHAR(128) NOT NULL UNIQUE,"
                + "token_expiry BIGINT UNSIGNED NOT NULL,"
                + "PRIMARY KEY (app_id, tenant_id, user_id, email, token),"
                + "FOREIGN KEY(app_id, tenant_id)"
                + " REFERENCES " + Config.getConfig(start).getTenantsTable() +  " (app_id, tenant_id) ON DELETE CASCADE"
                + ")";
    }


    static String getQueryToCreateEmailVerificationTokenExpiryIndex(Start start) {
        return "CREATE INDEX emailverification_tokens_index ON "
                + Config.getConfig(start).getEmailVerificationTokensTable() + "(token_expiry);";
    }

    public static void deleteExpiredEmailVerificationTokens(Start start) throws SQLException, StorageQueryException {
        String QUERY = "DELETE FROM " + getConfig(start).getEmailVerificationTokensTable() + " WHERE token_expiry < ?";

        update(start, QUERY, pst -> pst.setLong(1, currentTimeMillis()));
    }

    public static void updateUsersIsEmailVerified_Transaction(Start start, Connection con, AppIdentifier appIdentifier,
                                                              String userId, String email,
                                                              boolean isEmailVerified)
            throws SQLException, StorageQueryException {

        if (isEmailVerified) {
            String QUERY = "INSERT INTO " + getConfig(start).getEmailVerificationTable()
                    + "(app_id, user_id, email) VALUES(?, ?, ?)";

            update(con, QUERY, pst -> {
                pst.setString(1, appIdentifier.getAppId());
                pst.setString(2, userId);
                pst.setString(3, email);
            });
        } else {
            String QUERY = "DELETE FROM " + getConfig(start).getEmailVerificationTable()
                    + " WHERE app_id = ? AND user_id = ? AND email = ?";

            update(con, QUERY, pst -> {
                pst.setString(1, appIdentifier.getAppId());
                pst.setString(2, userId);
                pst.setString(3, email);
            });
        }
    }

    public static void deleteAllEmailVerificationTokensForUser_Transaction(Start start, Connection con,
                                                                           TenantIdentifier tenantIdentifier,
                                                                           String userId,
                                                                           String email)
            throws SQLException, StorageQueryException {
        String QUERY = "DELETE FROM " + getConfig(start).getEmailVerificationTokensTable()
                + " WHERE app_id = ? AND tenant_id = ? AND user_id = ? AND email = ?";

        update(con, QUERY, pst -> {
            pst.setString(1, tenantIdentifier.getAppId());
            pst.setString(2, tenantIdentifier.getTenantId());
            pst.setString(3, userId);
            pst.setString(4, email);
        });
    }

    public static EmailVerificationTokenInfo getEmailVerificationTokenInfo(Start start,
                                                                           TenantIdentifier tenantIdentifier,
                                                                           String token)
            throws SQLException, StorageQueryException {
        String QUERY = "SELECT user_id, token, token_expiry, email FROM "
                + getConfig(start).getEmailVerificationTokensTable()
                + " WHERE app_id = ? AND tenant_id = ? AND token = ?";
        return execute(start, QUERY, pst -> {
            pst.setString(1, tenantIdentifier.getAppId());
            pst.setString(2, tenantIdentifier.getTenantId());
            pst.setString(3, token);
        }, result -> {
            if (result.next()) {
                return EmailVerificationTokenInfoRowMapper.getInstance().mapOrThrow(result);
            }
            return null;
        });
    }

    public static void addEmailVerificationToken(Start start, TenantIdentifier tenantIdentifier, String userId,
                                                 String tokenHash, long expiry,
                                                 String email) throws SQLException, StorageQueryException {
        String QUERY = "INSERT INTO " + getConfig(start).getEmailVerificationTokensTable()
                + "(app_id, tenant_id, user_id, token, token_expiry, email)" + " VALUES(?, ?, ?, ?, ?, ?)";

        update(start, QUERY, pst -> {
            pst.setString(1, tenantIdentifier.getAppId());
            pst.setString(2, tenantIdentifier.getTenantId());
            pst.setString(3, userId);
            pst.setString(4, tokenHash);
            pst.setLong(5, expiry);
            pst.setString(6, email);
        });
    }

    public static EmailVerificationTokenInfo[] getAllEmailVerificationTokenInfoForUser_Transaction(Start start,
                                                                                                   Connection con,
                                                                                                   TenantIdentifier tenantIdentifier,
                                                                                                   String userId,
                                                                                                   String email)
            throws SQLException, StorageQueryException {

        String QUERY = "SELECT user_id, token, token_expiry, email FROM "
                + getConfig(start).getEmailVerificationTokensTable() +
                " WHERE app_id = ? AND tenant_id = ? AND user_id = ? AND email = ? FOR UPDATE";

        return execute(con, QUERY, pst -> {
            pst.setString(1, tenantIdentifier.getAppId());
            pst.setString(2, tenantIdentifier.getTenantId());
            pst.setString(3, userId);
            pst.setString(4, email);
        }, result -> {
            List<EmailVerificationTokenInfo> temp = new ArrayList<>();
            while (result.next()) {
                temp.add(EmailVerificationTokenInfoRowMapper.getInstance().mapOrThrow(result));
            }
            EmailVerificationTokenInfo[] finalResult = new EmailVerificationTokenInfo[temp.size()];
            for (int i = 0; i < temp.size(); i++) {
                finalResult[i] = temp.get(i);
            }
            return finalResult;
        });
    }

    public static EmailVerificationTokenInfo[] getAllEmailVerificationTokenInfoForUser(Start start,
                                                                                       TenantIdentifier tenantIdentifier,
                                                                                       String userId,
                                                                                       String email)
            throws SQLException, StorageQueryException {
        String QUERY = "SELECT user_id, token, token_expiry, email FROM "
                + getConfig(start).getEmailVerificationTokensTable() +
                " WHERE app_id = ? AND tenant_id = ? AND user_id = ? AND email = ?";

        return execute(start, QUERY, pst -> {
            pst.setString(1, tenantIdentifier.getAppId());
            pst.setString(2, tenantIdentifier.getTenantId());
            pst.setString(3, userId);
            pst.setString(4, email);
        }, result -> {
            List<EmailVerificationTokenInfo> temp = new ArrayList<>();
            while (result.next()) {
                temp.add(EmailVerificationTokenInfoRowMapper.getInstance().mapOrThrow(result));
            }
            EmailVerificationTokenInfo[] finalResult = new EmailVerificationTokenInfo[temp.size()];
            for (int i = 0; i < temp.size(); i++) {
                finalResult[i] = temp.get(i);
            }
            return finalResult;
        });
    }

    public static boolean isEmailVerified(Start start, AppIdentifier appIdentifier, String userId, String email)
            throws SQLException, StorageQueryException {
        String QUERY = "SELECT * FROM " + getConfig(start).getEmailVerificationTable()
                + " WHERE app_id = ? AND user_id = ? AND email = ?";

        return execute(start, QUERY, pst -> {
            pst.setString(1, appIdentifier.getAppId());
            pst.setString(2, userId);
            pst.setString(3, email);
        }, result -> result.next());
    }

    public static class UserIdAndEmail {
        public String userId;
        public String email;

        public UserIdAndEmail(String userId, String email) {
            this.userId = userId;
            this.email = email;
        }
    }

    // returns list of userIds where email is verified.
    public static List<String> isEmailVerified_transaction(Start start, Connection sqlCon, AppIdentifier appIdentifier,
                                                           List<UserIdAndEmail> userIdAndEmail)
            throws SQLException, StorageQueryException {
        if (userIdAndEmail.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> emails = new ArrayList<>();
        List<String> userIds = new ArrayList<>();
        Map<String, String> userIdToEmailMap = new HashMap<>();
        for (UserIdAndEmail ue : userIdAndEmail) {
            emails.add(ue.email);
            userIds.add(ue.userId);
        }
        for (UserIdAndEmail ue : userIdAndEmail) {
            if (userIdToEmailMap.containsKey(ue.userId)) {
                throw new RuntimeException("Found a bug!");
            }
            userIdToEmailMap.put(ue.userId, ue.email);
        }
        String QUERY = "SELECT * FROM " + getConfig(start).getEmailVerificationTable()
                + " WHERE app_id = ? AND user_id IN (" + Utils.generateCommaSeperatedQuestionMarks(userIds.size()) +
                ") AND email IN (" + Utils.generateCommaSeperatedQuestionMarks(emails.size()) + ")";

        return execute(sqlCon, QUERY, pst -> {
            pst.setString(1, appIdentifier.getAppId());
            int index = 2;
            for (String userId : userIds) {
                pst.setString(index++, userId);
            }
            for (String email : emails) {
                pst.setString(index++, email);
            }
        }, result -> {
            List<String> res = new ArrayList<>();
            while (result.next()) {
                String userId = result.getString("user_id");
                String email = result.getString("email");
                if (Objects.equals(userIdToEmailMap.get(userId), email)) {
                    res.add(userId);
                }
            }
            return res;
        });
    }

    public static List<String> isEmailVerified(Start start, AppIdentifier appIdentifier,
                                                           List<UserIdAndEmail> userIdAndEmail)
            throws SQLException, StorageQueryException {
        if (userIdAndEmail.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> emails = new ArrayList<>();
        List<String> userIds = new ArrayList<>();
        Map<String, String> userIdToEmailMap = new HashMap<>();
        for (UserIdAndEmail ue : userIdAndEmail) {
            emails.add(ue.email);
            userIds.add(ue.userId);
        }
        for (UserIdAndEmail ue : userIdAndEmail) {
            if (userIdToEmailMap.containsKey(ue.userId)) {
                throw new RuntimeException("Found a bug!");
            }
            userIdToEmailMap.put(ue.userId, ue.email);
        }
        String QUERY = "SELECT * FROM " + getConfig(start).getEmailVerificationTable()
                + " WHERE app_id = ? AND user_id IN (" + Utils.generateCommaSeperatedQuestionMarks(userIds.size()) +
                ") AND email IN (" + Utils.generateCommaSeperatedQuestionMarks(emails.size()) + ")";

        return execute(start, QUERY, pst -> {
            pst.setString(1, appIdentifier.getAppId());
            int index = 2;
            for (String userId : userIds) {
                pst.setString(index++, userId);
            }
            for (String email : emails) {
                pst.setString(index++, email);
            }
        }, result -> {
            List<String> res = new ArrayList<>();
            while (result.next()) {
                String userId = result.getString("user_id");
                String email = result.getString("email");
                if (Objects.equals(userIdToEmailMap.get(userId), email)) {
                    res.add(userId);
                }
            }
            return res;
        });
    }

    public static void deleteUserInfo_Transaction(Connection sqlCon, Start start, AppIdentifier appIdentifier,
                                                  String userId)
            throws StorageQueryException, SQLException {
        {
            String QUERY = "DELETE FROM " + getConfig(start).getEmailVerificationTable()
                    + " WHERE app_id = ? AND user_id = ?";
            update(sqlCon, QUERY, pst -> {
                pst.setString(1, appIdentifier.getAppId());
                pst.setString(2, userId);
            });
        }

        {
            String QUERY = "DELETE FROM " + getConfig(start).getEmailVerificationTokensTable()
                    + " WHERE app_id = ? AND user_id = ?";

            update(sqlCon, QUERY, pst -> {
                pst.setString(1, appIdentifier.getAppId());
                pst.setString(2, userId);
            });
        }
    }

    public static boolean deleteUserInfo(Start start, TenantIdentifier tenantIdentifier, String userId)
            throws StorageQueryException, SQLException {
        String QUERY = "DELETE FROM " + getConfig(start).getEmailVerificationTokensTable()
                + " WHERE app_id = ? AND tenant_id = ? AND user_id = ?";

        int numRows = update(start, QUERY, pst -> {
            pst.setString(1, tenantIdentifier.getAppId());
            pst.setString(2, tenantIdentifier.getTenantId());
            pst.setString(3, userId);
        });
        return numRows > 0;
    }

    public static void unverifyEmail(Start start, AppIdentifier appIdentifier, String userId, String email)
            throws SQLException, StorageQueryException {
        String QUERY = "DELETE FROM " + getConfig(start).getEmailVerificationTable()
                + " WHERE app_id = ? AND user_id = ? AND email = ?";

        update(start, QUERY, pst -> {
            pst.setString(1, appIdentifier.getAppId());
            pst.setString(2, userId);
            pst.setString(3, email);
        });
    }

    public static void revokeAllTokens(Start start, TenantIdentifier tenantIdentifier, String userId, String email)
            throws SQLException, StorageQueryException {
        String QUERY = "DELETE FROM " + getConfig(start).getEmailVerificationTokensTable()
                + " WHERE app_id = ? AND tenant_id = ? AND user_id = ? AND email = ?";

        update(start, QUERY, pst -> {
            pst.setString(1, tenantIdentifier.getAppId());
            pst.setString(2, tenantIdentifier.getTenantId());
            pst.setString(3, userId);
            pst.setString(4, email);
        });
    }

    public static boolean isUserIdBeingUsedForEmailVerification(Start start, AppIdentifier appIdentifier, String userId)
            throws SQLException, StorageQueryException {
        String QUERY = "SELECT * FROM " + getConfig(start).getEmailVerificationTokensTable()
                + " WHERE app_id = ? AND user_id = ?";

        return execute(start, QUERY, pst -> {
            pst.setString(1, appIdentifier.getAppId());
            pst.setString(2, userId);
        }, ResultSet::next);
    }

    private static class EmailVerificationTokenInfoRowMapper
            implements RowMapper<EmailVerificationTokenInfo, ResultSet> {
        private static final EmailVerificationTokenInfoRowMapper INSTANCE = new EmailVerificationTokenInfoRowMapper();

        private EmailVerificationTokenInfoRowMapper() {
        }

        private static EmailVerificationTokenInfoRowMapper getInstance() {
            return INSTANCE;
        }

        @Override
        public EmailVerificationTokenInfo map(ResultSet result) throws Exception {
            return new EmailVerificationTokenInfo(result.getString("user_id"), result.getString("token"),
                    result.getLong("token_expiry"), result.getString("email"));
        }
    }
}
