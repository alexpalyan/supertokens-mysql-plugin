package io.supertokens.storage.mysql.queries;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import io.supertokens.pluginInterface.RowMapper;
import io.supertokens.pluginInterface.exceptions.StorageQueryException;
import io.supertokens.pluginInterface.exceptions.StorageTransactionLogicException;
import io.supertokens.pluginInterface.multitenancy.AppIdentifier;
import io.supertokens.pluginInterface.multitenancy.TenantIdentifier;
import io.supertokens.pluginInterface.totp.TOTPDevice;
import io.supertokens.pluginInterface.totp.TOTPUsedCode;
import io.supertokens.storage.mysql.Start;
import io.supertokens.storage.mysql.config.Config;

import static io.supertokens.storage.mysql.QueryExecutorTemplate.execute;
import static io.supertokens.storage.mysql.QueryExecutorTemplate.update;

public class TOTPQueries {
    public static String getQueryToCreateUsersTable(Start start) {
        return "CREATE TABLE IF NOT EXISTS " + Config.getConfig(start).getTotpUsersTable() + " ("
                + "app_id VARCHAR(64) DEFAULT 'public',"
                + "user_id VARCHAR(128) NOT NULL,"
                + "PRIMARY KEY (app_id, user_id),"
                + "FOREIGN KEY(app_id)"
                + " REFERENCES " + Config.getConfig(start).getAppsTable() +  " (app_id) ON DELETE CASCADE"
                + ")";
    }

    public static String getQueryToCreateUserDevicesTable(Start start) {
        return "CREATE TABLE IF NOT EXISTS " + Config.getConfig(start).getTotpUserDevicesTable() + " ("
                + "app_id VARCHAR(64) DEFAULT 'public',"
                + "user_id VARCHAR(128) NOT NULL," 
				+ "device_name VARCHAR(256) NOT NULL,"
                + "secret_key VARCHAR(256) NOT NULL,"
                + "period INTEGER NOT NULL," 
				+ "skew INTEGER NOT NULL," 
				+ "verified BOOLEAN NOT NULL,"
                + "PRIMARY KEY (app_id, user_id, device_name),"
                + "FOREIGN KEY (app_id, user_id)"
                + " REFERENCES " + Config.getConfig(start).getTotpUsersTable() + "(app_id, user_id) ON DELETE CASCADE"
                + ");";
    }

    public static String getQueryToCreateUsedCodesTable(Start start) {
        return "CREATE TABLE IF NOT EXISTS " + Config.getConfig(start).getTotpUsedCodesTable() + " ("
                + "app_id VARCHAR(64) DEFAULT 'public',"
                + "tenant_id VARCHAR(64) DEFAULT 'public',"
                + "user_id VARCHAR(128) NOT NULL, "
                + "code VARCHAR(8) NOT NULL," + "is_valid BOOLEAN NOT NULL,"
                + "expiry_time_ms BIGINT UNSIGNED NOT NULL,"
                + "created_time_ms BIGINT UNSIGNED NOT NULL,"
                + "PRIMARY KEY (app_id, tenant_id, user_id, created_time_ms),"
                + "FOREIGN KEY (app_id, user_id)"
                + " REFERENCES " + Config.getConfig(start).getTotpUsersTable() + "(app_id, user_id) ON DELETE CASCADE,"
                + "FOREIGN KEY (app_id, tenant_id)"
                + " REFERENCES " + Config.getConfig(start).getTenantsTable() + " (app_id, tenant_id) ON DELETE CASCADE"
                + ");";
    }

    public static String getQueryToCreateUsedCodesExpiryTimeIndex(Start start) {
        return "CREATE INDEX totp_used_codes_expiry_time_ms_index ON "
                + Config.getConfig(start).getTotpUsedCodesTable() + " (app_id, tenant_id, expiry_time_ms)";
    }

    private static int insertUser_Transaction(Start start, Connection con, AppIdentifier appIdentifier, String userId)
            throws SQLException, StorageQueryException {
        // Create user if not exists:
        // ON CONFLICT DO NOTHING
        String QUERY = "INSERT INTO " + Config.getConfig(start).getTotpUsersTable()
                + "(app_id, user_id) "
                + "SELECT ?, ? WHERE NOT EXISTS ("
                + " SELECT app_id, user_id FROM " + Config.getConfig(start).getTotpUsersTable()
                + " WHERE app_id = ? AND user_id = ?"
                + ")";

        return update(con, QUERY, pst -> {
            pst.setString(1, appIdentifier.getAppId());
            pst.setString(2, userId);
            pst.setString(3, appIdentifier.getAppId());
            pst.setString(4, userId);
        });
    }

    private static int insertDevice_Transaction(Start start, Connection con, AppIdentifier appIdentifier, TOTPDevice device)
            throws SQLException, StorageQueryException {
        String QUERY = "INSERT INTO " + Config.getConfig(start).getTotpUserDevicesTable()
                + " (app_id, user_id, device_name, secret_key, period, skew, verified) VALUES (?, ?, ?, ?, ?, ?, ?)";

        return update(con, QUERY, pst -> {
            pst.setString(1, appIdentifier.getAppId());
            pst.setString(2, device.userId);
            pst.setString(3, device.deviceName);
            pst.setString(4, device.secretKey);
            pst.setInt(5, device.period);
            pst.setInt(6, device.skew);
            pst.setBoolean(7, device.verified);
        });
    }

    public static void createDevice(Start start, AppIdentifier appIdentifier, TOTPDevice device)
            throws StorageQueryException, StorageTransactionLogicException {
        start.startTransaction(con -> {
            Connection sqlCon = (Connection) con.getConnection();

            try {
                insertUser_Transaction(start, sqlCon, appIdentifier, device.userId);
                insertDevice_Transaction(start, sqlCon, appIdentifier, device);
                sqlCon.commit();
            } catch (SQLException e) {
                throw new StorageTransactionLogicException(e);
            }

            return null;
        });
        return;
    }

    public static int markDeviceAsVerified(Start start, AppIdentifier appIdentifier, String userId, String deviceName)
            throws StorageQueryException, SQLException {
        String QUERY = "UPDATE " + Config.getConfig(start).getTotpUserDevicesTable()
                + " SET verified = true WHERE app_id = ? AND user_id = ? AND device_name = ?";
        return update(start, QUERY, pst -> {
            pst.setString(1, appIdentifier.getAppId());
            pst.setString(2, userId);
            pst.setString(3, deviceName);
        });
    }

    public static int deleteDevice_Transaction(Start start, Connection con, AppIdentifier appIdentifier, String userId, String deviceName)
            throws SQLException, StorageQueryException {
        String QUERY = "DELETE FROM " + Config.getConfig(start).getTotpUserDevicesTable()
                + " WHERE app_id = ? AND user_id = ? AND device_name = ?;";

        return update(con, QUERY, pst -> {
            pst.setString(1, appIdentifier.getAppId());
            pst.setString(2, userId);
            pst.setString(3, deviceName);
        });
    }

    public static int removeUser_Transaction(Start start, Connection con, AppIdentifier appIdentifier, String userId)
            throws SQLException, StorageQueryException {
        String QUERY = "DELETE FROM " + Config.getConfig(start).getTotpUsersTable()
                + " WHERE app_id = ? AND user_id = ?;";
        int removedUsersCount = update(con, QUERY, pst -> {
            pst.setString(1, appIdentifier.getAppId());
            pst.setString(2, userId);
        });

        return removedUsersCount;
    }

    public static boolean removeUser(Start start, TenantIdentifier tenantIdentifier, String userId)
            throws SQLException, StorageQueryException {
        String QUERY = "DELETE FROM " + Config.getConfig(start).getTotpUsedCodesTable()
                + " WHERE app_id = ? AND tenant_id = ? AND user_id = ?;";
        int removedUsersCount = update(start, QUERY, pst -> {
            pst.setString(1, tenantIdentifier.getAppId());
            pst.setString(2, tenantIdentifier.getTenantId());
            pst.setString(3, userId);
        });

        return removedUsersCount > 0;
    }

    public static int updateDeviceName(Start start, AppIdentifier appIdentifier, String userId, String oldDeviceName, String newDeviceName)
            throws StorageQueryException, SQLException {
        String QUERY = "UPDATE " + Config.getConfig(start).getTotpUserDevicesTable()
                + " SET device_name = ? WHERE app_id = ? AND user_id = ? AND device_name = ?;";

        return update(start, QUERY, pst -> {
            pst.setString(1, newDeviceName);
            pst.setString(2, appIdentifier.getAppId());
            pst.setString(3, userId);
            pst.setString(4, oldDeviceName);
        });
    }

    public static TOTPDevice[] getDevices(Start start, AppIdentifier appIdentifier, String userId)
            throws StorageQueryException, SQLException {
        String QUERY = "SELECT * FROM " + Config.getConfig(start).getTotpUserDevicesTable()
                + " WHERE app_id = ? AND user_id = ?;";

        return execute(start, QUERY, pst -> {
            pst.setString(1, appIdentifier.getAppId());
            pst.setString(2, userId);
        }, result -> {
            List<TOTPDevice> devices = new ArrayList<>();
            while (result.next()) {
                devices.add(TOTPDeviceRowMapper.getInstance().map(result));
            }

            return devices.toArray(TOTPDevice[]::new);
        });
    }

    public static TOTPDevice[] getDevices_Transaction(Start start, Connection con, AppIdentifier appIdentifier, String userId)
            throws StorageQueryException, SQLException {
        String QUERY = "SELECT * FROM " + Config.getConfig(start).getTotpUserDevicesTable()
                + " WHERE app_id = ? AND user_id = ? FOR UPDATE;";

        return execute(con, QUERY, pst -> {
            pst.setString(1, appIdentifier.getAppId());
            pst.setString(2, userId);
        }, result -> {
            List<TOTPDevice> devices = new ArrayList<>();
            while (result.next()) {
                devices.add(TOTPDeviceRowMapper.getInstance().map(result));
            }

            return devices.toArray(TOTPDevice[]::new);
        });

    }

    public static int insertUsedCode_Transaction(Start start, Connection con, TenantIdentifier tenantIdentifier, TOTPUsedCode code)
            throws SQLException, StorageQueryException {
        String QUERY = "INSERT INTO " + Config.getConfig(start).getTotpUsedCodesTable()
                + " (app_id, tenant_id, user_id, code, is_valid, expiry_time_ms, created_time_ms) VALUES (?, ?, ?, ?, ?, ?, ?);";

        return update(con, QUERY, pst -> {
            pst.setString(1, tenantIdentifier.getAppId());
            pst.setString(2, tenantIdentifier.getTenantId());
            pst.setString(3, code.userId);
            pst.setString(4, code.code);
            pst.setBoolean(5, code.isValid);
            pst.setLong(6, code.expiryTime);
            pst.setLong(7, code.createdTime);
        });
    }

    /**
     * Query to get all used codes (expired/non-expired) for a user in descending
     * order of creation time.
     */
    public static TOTPUsedCode[] getAllUsedCodesDescOrder_Transaction(Start start, Connection con,
                                                                      TenantIdentifier tenantIdentifier, String userId)
            throws SQLException, StorageQueryException {
        // Take a lock based on the user id:
        String QUERY = "SELECT * FROM " +
                Config.getConfig(start).getTotpUsedCodesTable()
                + " WHERE app_id = ? AND tenant_id = ? AND user_id = ? ORDER BY created_time_ms DESC FOR UPDATE;";
        return execute(con, QUERY, pst -> {
            pst.setString(1, tenantIdentifier.getAppId());
            pst.setString(2, tenantIdentifier.getTenantId());
            pst.setString(3, userId);
        }, result -> {
            List<TOTPUsedCode> codes = new ArrayList<>();
            while (result.next()) {
                codes.add(TOTPUsedCodeRowMapper.getInstance().map(result));
            }

            return codes.toArray(TOTPUsedCode[]::new);
        });
    }

    public static int removeExpiredCodes(Start start, TenantIdentifier tenantIdentifier, long expiredBefore)
            throws StorageQueryException, SQLException {
        String QUERY = "DELETE FROM " + Config.getConfig(start).getTotpUsedCodesTable()
                + " WHERE app_id = ? AND tenant_id = ? AND expiry_time_ms < ?;";

        return update(start, QUERY, pst -> {
            pst.setString(1, tenantIdentifier.getAppId());
            pst.setString(2, tenantIdentifier.getTenantId());
            pst.setLong(3, expiredBefore);
        });
    }

    private static class TOTPDeviceRowMapper implements RowMapper<TOTPDevice, ResultSet> {
        private static final TOTPDeviceRowMapper INSTANCE = new TOTPDeviceRowMapper();

        private TOTPDeviceRowMapper() {
        }

        private static TOTPDeviceRowMapper getInstance() {
            return INSTANCE;
        }

        @Override
        public TOTPDevice map(ResultSet result) throws SQLException {
            return new TOTPDevice(
                    result.getString("user_id"),
                    result.getString("device_name"),
                    result.getString("secret_key"),
                    result.getInt("period"),
                    result.getInt("skew"),
                    result.getBoolean("verified"));
        }
    }

    private static class TOTPUsedCodeRowMapper implements RowMapper<TOTPUsedCode, ResultSet> {
        private static final TOTPUsedCodeRowMapper INSTANCE = new TOTPUsedCodeRowMapper();

        private TOTPUsedCodeRowMapper() {
        }

        private static TOTPUsedCodeRowMapper getInstance() {
            return INSTANCE;
        }

        @Override
        public TOTPUsedCode map(ResultSet result) throws SQLException {
            return new TOTPUsedCode(
                    result.getString("user_id"),
                    result.getString("code"),
                    result.getBoolean("is_valid"),
                    result.getLong("expiry_time_ms"),
                    result.getLong("created_time_ms"));
        }
    }
}
