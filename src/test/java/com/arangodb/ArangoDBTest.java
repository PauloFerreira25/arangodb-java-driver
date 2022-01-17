/*
 * DISCLAIMER
 *
 * Copyright 2016 ArangoDB GmbH, Cologne, Germany
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright holder is ArangoDB GmbH, Cologne, Germany
 */

package com.arangodb;

import com.arangodb.entity.*;
import com.arangodb.model.*;
import com.arangodb.model.LogOptions.SortOrder;
import com.arangodb.util.TestUtils;
import com.arangodb.velocypack.exception.VPackException;
import com.arangodb.velocystream.Request;
import com.arangodb.velocystream.RequestType;
import com.arangodb.velocystream.Response;
import org.hamcrest.Matcher;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * @author Mark Vollmary
 * @author Reşat SABIQ
 */
@RunWith(Parameterized.class)
public class ArangoDBTest {

    private static final DbName DB1 = DbName.of("ArangoDBTest_db1");
    private static final DbName DB2 = DbName.of("ArangoDBTest_db2");

    private static final String ROOT = "root";
    private static final String PW = "machts der hund";

    private final ArangoDB arangoDB;
    private final ArangoDatabase db1;
    private final ArangoDatabase db2;

    private static Boolean extendedNames;

    @BeforeClass
    public static void initDB() {
        ArangoDB arango = BaseTest.arangos.get(0);
        for (DbName db : new DbName[]{DB1, DB2}) {
            ArangoDatabase database = arango.db(db);
            if (!database.exists())
                database.create();
        }
    }

    @AfterClass
    public static void shutdown() {
        ArangoDB arango = BaseTest.arangos.get(0);
        for (DbName db : new DbName[]{DB1, DB2}) {
            ArangoDatabase database = arango.db(db);
            if (database.exists())
                database.drop();
        }
        BaseTest.arangos.forEach(ArangoDB::shutdown);
    }

    @Parameters
    public static List<ArangoDB> builders() {
        return BaseTest.arangos;
    }

    public ArangoDBTest(final ArangoDB arangoDB) {
        super();
        this.arangoDB = arangoDB;
        db1 = arangoDB.db(DB1);
        db2 = arangoDB.db(DB2);
    }

    private boolean isEnterprise() {
        return arangoDB.getVersion().getLicense() == License.ENTERPRISE;
    }

    private boolean isCluster() {
        return arangoDB.getRole() == ServerRole.COORDINATOR;
    }

    private boolean isAtLeastVersion(final int major, final int minor) {
        return TestUtils.isAtLeastVersion(arangoDB.getVersion().getVersion(), major, minor, 0);
    }

    private boolean supportsExtendedNames() {
        if (extendedNames == null) {
            try {
                ArangoDatabase testDb = arangoDB.db(DbName.of("test-" + TestUtils.generateRandomDbName(20, true)));
                testDb.create();
                extendedNames = true;
                testDb.drop();
            } catch (ArangoDBException e) {
                extendedNames = false;
            }
        }
        return extendedNames;
    }

    @Test
    public void getVersion() {
        final ArangoDBVersion version = arangoDB.getVersion();
        assertThat(version, is(notNullValue()));
        assertThat(version.getServer(), is(notNullValue()));
        assertThat(version.getVersion(), is(notNullValue()));
    }

    @Test
    public void createAndDeleteDatabase() {
        final DbName dbName = DbName.of("testDB-" + TestUtils.generateRandomDbName(20, supportsExtendedNames()));
        final Boolean resultCreate;
        resultCreate = arangoDB.createDatabase(dbName);
        assertThat(resultCreate, is(true));
        final Boolean resultDelete = arangoDB.db(dbName).drop();
        assertThat(resultDelete, is(true));
    }

    @Test
    public void createWithNotNormalizedName() {
        assumeTrue(supportsExtendedNames());

        final String dbName = "testDB-\u006E\u0303\u00f1";
        DbName normalized = DbName.normalize(dbName);
        arangoDB.createDatabase(normalized);
        arangoDB.db(normalized).drop();

        try {
            DbName.of(dbName);
            fail();
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString("not normalized"));
        }
    }

    @Test
    public void createDatabaseWithOptions() {
        assumeTrue(isCluster());
        assumeTrue(isAtLeastVersion(3, 6));
        final DbName dbName = DbName.of("testDB-" + TestUtils.generateRandomDbName(20, supportsExtendedNames()));
        final Boolean resultCreate = arangoDB.createDatabase(new DBCreateOptions()
                .name(dbName)
                .options(new DatabaseOptions()
                        .writeConcern(2)
                        .replicationFactor(2)
                        .sharding("")
                )
        );
        assertThat(resultCreate, is(true));

        DatabaseEntity info = arangoDB.db(dbName).getInfo();
        assertThat(info.getReplicationFactor(), is(2));
        assertThat(info.getWriteConcern(), is(2));
        assertThat(info.getSharding(), is(""));
        assertThat(info.getSatellite(), nullValue());

        final Boolean resultDelete = arangoDB.db(dbName).drop();
        assertThat(resultDelete, is(true));
    }

    @Test
    public void createDatabaseWithOptionsSatellite() {
        assumeTrue(isCluster());
        assumeTrue(isEnterprise());
        assumeTrue(isAtLeastVersion(3, 6));

        final DbName dbName = DbName.of("testDB-" + TestUtils.generateRandomDbName(20, supportsExtendedNames()));
        final Boolean resultCreate = arangoDB.createDatabase(new DBCreateOptions()
                .name(dbName)
                .options(new DatabaseOptions()
                        .writeConcern(2)
                        .satellite(true)
                        .sharding("")
                )
        );
        assertThat(resultCreate, is(true));

        DatabaseEntity info = arangoDB.db(dbName).getInfo();
        assertThat(info.getReplicationFactor(), nullValue());
        assertThat(info.getSatellite(), is(true));
        assertThat(info.getWriteConcern(), is(2));
        assertThat(info.getSharding(), is(""));

        final Boolean resultDelete = arangoDB.db(dbName).drop();
        assertThat(resultDelete, is(true));
    }

    @Test
    public void createDatabaseWithUsers() throws InterruptedException {
        final DbName dbName = DbName.of("testDB-" + TestUtils.generateRandomDbName(20, supportsExtendedNames()));
        final Map<String, Object> extra = Collections.singletonMap("key", "value");
        final Boolean resultCreate = arangoDB.createDatabase(new DBCreateOptions()
                .name(dbName)
                .users(Collections.singletonList(new DatabaseUsersOptions()
                        .active(true)
                        .username("testUser")
                        .passwd("testPasswd")
                        .extra(extra)
                ))
        );
        assertThat(resultCreate, is(true));

        DatabaseEntity info = arangoDB.db(dbName).getInfo();
        assertThat(info.getName(), is(dbName.get()));

        Optional<UserEntity> retrievedUserOptional = arangoDB.getUsers().stream()
                .filter(it -> it.getUser().equals("testUser"))
                .findFirst();
        assertThat(retrievedUserOptional.isPresent(), is(true));

        UserEntity retrievedUser = retrievedUserOptional.get();
        assertThat(retrievedUser.getActive(), is(true));
        assertThat(retrievedUser.getExtra(), is(extra));

        // needed for active-failover tests only
        Thread.sleep(1_000);

        ArangoDB arangoDBTestUser = new ArangoDB.Builder()
                .user("testUser")
                .password("testPasswd")
                .build();

        // check if testUser has been created and can access the created db
        ArangoCollection collection = arangoDBTestUser.db(dbName).collection("col-" + UUID.randomUUID().toString());
        collection.create();
        arangoDBTestUser.shutdown();

        final Boolean resultDelete = arangoDB.db(dbName).drop();
        assertThat(resultDelete, is(true));
    }

    @Test
    public void getDatabases() {
        Collection<String> dbs = arangoDB.getDatabases();
        assertThat(dbs, is(notNullValue()));
        assertThat(dbs.size(), is(greaterThan(0)));
        assertThat(dbs.contains("_system"), is(true));
        assertThat(dbs, hasItem(DB1.get()));
    }

    @Test
    public void getAccessibleDatabases() {
        final Collection<String> dbs = arangoDB.getAccessibleDatabases();
        assertThat(dbs, is(notNullValue()));
        assertThat(dbs.size(), greaterThan(0));
        assertThat(dbs, hasItem("_system"));
    }

    @Test
    public void getAccessibleDatabasesFor() {
        final Collection<String> dbs = arangoDB.getAccessibleDatabasesFor("root");
        assertThat(dbs, is(notNullValue()));
        assertThat(dbs.size(), greaterThan(0));
        assertThat(dbs, hasItem("_system"));
    }

    @Test
    public void createUser() {
        String username = "user-" + UUID.randomUUID().toString();
        final UserEntity result = arangoDB.createUser(username, PW, null);
        assertThat(result, is(notNullValue()));
        assertThat(result.getUser(), is(username));
    }

    @Test
    public void deleteUser() {
        String username = "user-" + UUID.randomUUID().toString();
        arangoDB.createUser(username, PW, null);
        arangoDB.deleteUser(username);
    }

    @Test
    public void getUserRoot() {
        final UserEntity user = arangoDB.getUser(ROOT);
        assertThat(user, is(notNullValue()));
        assertThat(user.getUser(), is(ROOT));
    }

    @Test
    public void getUser() {
        String username = "user-" + UUID.randomUUID().toString();
        arangoDB.createUser(username, PW, null);
        final UserEntity user = arangoDB.getUser(username);
        assertThat(user.getUser(), is(username));

    }

    @Test
    public void getUsersOnlyRoot() {
        final Collection<UserEntity> users = arangoDB.getUsers();
        assertThat(users, is(notNullValue()));
        assertThat(users.size(), greaterThan(0));
    }

    @Test
    public void getUsers() {
        String username = "user-" + UUID.randomUUID().toString();
        // Allow & account for pre-existing users other than ROOT:
        final Collection<UserEntity> initialUsers = arangoDB.getUsers();

        arangoDB.createUser(username, PW, null);
        final Collection<UserEntity> users = arangoDB.getUsers();
        assertThat(users, is(notNullValue()));
        assertThat(users.size(), is(initialUsers.size() + 1));

        final List<Matcher<? super String>> matchers = new ArrayList<>(users.size());
        // Add initial users, including root:
        for (final UserEntity userEntity : initialUsers) {
            matchers.add(is(userEntity.getUser()));
        }
        // Add username:
        matchers.add(is(username));

        for (final UserEntity user : users) {
            assertThat(user.getUser(), anyOf(matchers));
        }
    }

    @Test
    public void updateUserNoOptions() {
        String username = "user-" + UUID.randomUUID().toString();
        arangoDB.createUser(username, PW, null);
        arangoDB.updateUser(username, null);
    }

    @Test
    public void updateUser() {
        String username = "user-" + UUID.randomUUID().toString();
        final Map<String, Object> extra = new HashMap<>();
        extra.put("hund", false);
        arangoDB.createUser(username, PW, new UserCreateOptions().extra(extra));
        extra.put("hund", true);
        extra.put("mund", true);
        final UserEntity user = arangoDB.updateUser(username, new UserUpdateOptions().extra(extra));
        assertThat(user, is(notNullValue()));
        assertThat(user.getExtra().size(), is(2));
        assertThat(user.getExtra().get("hund"), is(notNullValue()));
        assertThat(Boolean.valueOf(String.valueOf(user.getExtra().get("hund"))), is(true));
        final UserEntity user2 = arangoDB.getUser(username);
        assertThat(user2.getExtra().size(), is(2));
        assertThat(user2.getExtra().get("hund"), is(notNullValue()));
        assertThat(Boolean.valueOf(String.valueOf(user2.getExtra().get("hund"))), is(true));
    }

    @Test
    public void replaceUser() {
        String username = "user-" + UUID.randomUUID().toString();
        final Map<String, Object> extra = new HashMap<>();
        extra.put("hund", false);
        arangoDB.createUser(username, PW, new UserCreateOptions().extra(extra));
        extra.remove("hund");
        extra.put("mund", true);
        final UserEntity user = arangoDB.replaceUser(username, new UserUpdateOptions().extra(extra));
        assertThat(user, is(notNullValue()));
        assertThat(user.getExtra().size(), is(1));
        assertThat(user.getExtra().get("mund"), is(notNullValue()));
        assertThat(Boolean.valueOf(String.valueOf(user.getExtra().get("mund"))), is(true));
        final UserEntity user2 = arangoDB.getUser(username);
        assertThat(user2.getExtra().size(), is(1));
        assertThat(user2.getExtra().get("mund"), is(notNullValue()));
        assertThat(Boolean.valueOf(String.valueOf(user2.getExtra().get("mund"))), is(true));
    }

    @Test
    public void updateUserDefaultDatabaseAccess() {
        String username = "user-" + UUID.randomUUID().toString();
        arangoDB.createUser(username, PW);
        arangoDB.grantDefaultDatabaseAccess(username, Permissions.RW);
    }

    @Test
    public void updateUserDefaultCollectionAccess() {
        String username = "user-" + UUID.randomUUID().toString();
        arangoDB.createUser(username, PW);
        arangoDB.grantDefaultCollectionAccess(username, Permissions.RW);
    }

    @Test
    public void authenticationFailPassword() {
        final ArangoDB arangoDB = new ArangoDB.Builder().password("no").jwt(null).build();
        try {
            arangoDB.getVersion();
            fail();
        } catch (final ArangoDBException e) {
            assertThat(e.getResponseCode(), is(401));
        }
    }

    @Test
    public void authenticationFailUser() {
        final ArangoDB arangoDB = new ArangoDB.Builder().user("no").jwt(null).build();
        try {
            arangoDB.getVersion();
            fail();
        } catch (final ArangoDBException e) {
            assertThat(e.getResponseCode(), is(401));
        }
    }

    @Test
    public void execute() throws VPackException {
        final Response response = arangoDB.execute(new Request(DbName.SYSTEM, RequestType.GET, "/_api/version"));
        assertThat(response.getBody(), is(notNullValue()));
        assertThat(response.getBody().get("version").isString(), is(true));
    }

    @Test
    public void getLogs() {
        assumeTrue(isAtLeastVersion(3, 7)); // it fails in 3.6 active-failover (BTS-362)
        final LogEntity logs = arangoDB.getLogs(null);
        assertThat(logs, is(notNullValue()));
        assertThat(logs.getTotalAmount(), greaterThan(0L));
        assertThat((long) logs.getLid().size(), is(logs.getTotalAmount()));
        assertThat((long) logs.getLevel().size(), is(logs.getTotalAmount()));
        assertThat((long) logs.getTimestamp().size(), is(logs.getTotalAmount()));
        assertThat((long) logs.getText().size(), is(logs.getTotalAmount()));
    }

    @Test
    public void getLogsUpto() {
        assumeTrue(isAtLeastVersion(3, 7)); // it fails in 3.6 active-failover (BTS-362)
        final LogEntity logsUpto = arangoDB.getLogs(new LogOptions().upto(LogLevel.WARNING));
        assertThat(logsUpto, is(notNullValue()));
        assertThat(logsUpto.getLevel(), not(contains(LogLevel.INFO)));
    }

    @Test
    public void getLogsLevel() {
        assumeTrue(isAtLeastVersion(3, 7)); // it fails in 3.6 active-failover (BTS-362)
        final LogEntity logsInfo = arangoDB.getLogs(new LogOptions().level(LogLevel.INFO));
        assertThat(logsInfo, is(notNullValue()));
        assertThat(logsInfo.getLevel(), everyItem(is(LogLevel.INFO)));
    }

    @Test
    public void getLogsStart() {
        assumeTrue(isAtLeastVersion(3, 7)); // it fails in 3.6 active-failover (BTS-362)
        final LogEntity logs = arangoDB.getLogs(null);
        assertThat(logs.getLid(), not(empty()));
        final LogEntity logsStart = arangoDB.getLogs(new LogOptions().start(logs.getLid().get(0) + 1));
        assertThat(logsStart, is(notNullValue()));
        assertThat(logsStart.getLid(), not(contains(logs.getLid().get(0))));
    }

    @Test
    public void getLogsSize() {
        assumeTrue(isAtLeastVersion(3, 7)); // it fails in 3.6 active-failover (BTS-362)
        final LogEntity logs = arangoDB.getLogs(null);
        assertThat(logs.getLid().size(), greaterThan(0));
        final LogEntity logsSize = arangoDB.getLogs(new LogOptions().size(logs.getLid().size() - 1));
        assertThat(logsSize, is(notNullValue()));
        assertThat(logsSize.getLid().size(), is(logs.getLid().size() - 1));
    }

    @Test
    public void getLogsOffset() {
        assumeTrue(isAtLeastVersion(3, 7)); // it fails in 3.6 active-failover (BTS-362)
        final LogEntity logs = arangoDB.getLogs(null);
        assertThat(logs.getTotalAmount(), greaterThan(0L));
        final LogEntity logsOffset = arangoDB.getLogs(new LogOptions().offset(1));
        assertThat(logsOffset, is(notNullValue()));
        assertThat(logsOffset.getLid(), not(hasItem(logs.getLid().get(0))));
    }

    @Test
    public void getLogsSearch() {
        assumeTrue(isAtLeastVersion(3, 7)); // it fails in 3.6 active-failover (BTS-362)
        final LogEntity logs = arangoDB.getLogs(null);
        final LogEntity logsSearch = arangoDB.getLogs(new LogOptions().search(BaseTest.TEST_DB.get()));
        assertThat(logsSearch, is(notNullValue()));
        assertThat(logs.getTotalAmount(), greaterThan(logsSearch.getTotalAmount()));
    }

    @Test
    public void getLogsSortAsc() {
        assumeTrue(isAtLeastVersion(3, 7)); // it fails in 3.6 active-failover (BTS-362)
        final LogEntity logs = arangoDB.getLogs(new LogOptions().sort(SortOrder.asc));
        assertThat(logs, is(notNullValue()));
        long lastId = -1;
        for (final Long id : logs.getLid()) {
            assertThat(id, greaterThan(lastId));
            lastId = id;
        }
    }

    @Test
    public void getLogsSortDesc() {
        assumeTrue(isAtLeastVersion(3, 7)); // it fails in 3.6 active-failover (BTS-362)
        final LogEntity logs = arangoDB.getLogs(new LogOptions().sort(SortOrder.desc));
        assertThat(logs, is(notNullValue()));
        long lastId = Long.MAX_VALUE;
        for (final Long id : logs.getLid()) {
            assertThat(lastId, greaterThan(id));
            lastId = id;
        }
    }

    @Test
    public void getLogEntries() {
        assumeTrue(isAtLeastVersion(3, 8));
        final LogEntriesEntity logs = arangoDB.getLogEntries(null);
        assertThat(logs, is(notNullValue()));
        assertThat(logs.getTotal(), greaterThan(0L));
        assertThat((long) logs.getMessages().size(), is(logs.getTotal()));
    }

    @Test
    public void getLogEntriesUpto() {
        assumeTrue(isAtLeastVersion(3, 8));
        final LogEntriesEntity logsUpto = arangoDB.getLogEntries(new LogOptions().upto(LogLevel.WARNING));
        assertThat(logsUpto, is(notNullValue()));
        assertThat(
                logsUpto.getMessages().stream()
                        .map(LogEntriesEntity.Message::getLevel)
                        .noneMatch("INFO"::equals),
                is(true)
        );
    }

    @Test
    public void getLogEntriesLevel() {
        assumeTrue(isAtLeastVersion(3, 8));
        final LogEntriesEntity logsInfo = arangoDB.getLogEntries(new LogOptions().level(LogLevel.INFO));
        assertThat(logsInfo, is(notNullValue()));
        assertThat(
                logsInfo.getMessages().stream()
                        .map(LogEntriesEntity.Message::getLevel)
                        .allMatch("INFO"::equals),
                is(true)
        );
    }

    @Test
    public void getLogEntriesStart() {
        assumeTrue(isAtLeastVersion(3, 8));
        final LogEntriesEntity logs = arangoDB.getLogEntries(null);
        final Long firstId = logs.getMessages().get(0).getId();
        final LogEntriesEntity logsStart = arangoDB.getLogEntries(new LogOptions().start(firstId + 1));
        assertThat(logsStart, is(notNullValue()));
        assertThat(
                logsStart.getMessages().stream()
                        .map(LogEntriesEntity.Message::getId)
                        .filter(firstId::equals)
                        .count(),
                is(0L));
    }

    @Test
    public void getLogEntriesSize() {
        assumeTrue(isAtLeastVersion(3, 8));
        final LogEntriesEntity logs = arangoDB.getLogEntries(null);
        int count = logs.getMessages().size();
        assertThat(count, greaterThan(0));
        final LogEntriesEntity logsSize = arangoDB.getLogEntries(new LogOptions().size(count - 1));
        assertThat(logsSize, is(notNullValue()));
        assertThat(logsSize.getMessages().size(), is(count - 1));
    }

    @Test
    public void getLogEntriesOffset() {
        assumeTrue(isAtLeastVersion(3, 8));
        final LogEntriesEntity logs = arangoDB.getLogEntries(null);
        assertThat(logs.getTotal(), greaterThan(0L));
        Long firstId = logs.getMessages().get(0).getId();
        final LogEntriesEntity logsOffset = arangoDB.getLogEntries(new LogOptions().offset(1));
        assertThat(logsOffset, is(notNullValue()));
        assertThat(logsOffset.getMessages().stream()
                        .map(LogEntriesEntity.Message::getId)
                        .filter(firstId::equals)
                        .count()
                , is(0L));
    }

    @Test
    public void getLogEntriesSearch() {
        assumeTrue(isAtLeastVersion(3, 8));
        final LogEntriesEntity logs = arangoDB.getLogEntries(null);
        final LogEntriesEntity logsSearch = arangoDB.getLogEntries(new LogOptions().search(BaseTest.TEST_DB.get()));
        assertThat(logsSearch, is(notNullValue()));
        assertThat(logs.getTotal(), greaterThan(logsSearch.getTotal()));
    }

    @Test
    public void getLogEntriesSortAsc() {
        assumeTrue(isAtLeastVersion(3, 8));
        final LogEntriesEntity logs = arangoDB.getLogEntries(new LogOptions().sort(SortOrder.asc));
        assertThat(logs, is(notNullValue()));
        long lastId = -1;
        List<Long> ids = logs.getMessages().stream()
                .map(LogEntriesEntity.Message::getId)
                .collect(Collectors.toList());
        for (final Long id : ids) {
            assertThat(id, greaterThan(lastId));
            lastId = id;
        }
    }

    @Test
    public void getLogEntriesSortDesc() {
        assumeTrue(isAtLeastVersion(3, 8));
        final LogEntriesEntity logs = arangoDB.getLogEntries(new LogOptions().sort(SortOrder.desc));
        assertThat(logs, is(notNullValue()));
        long lastId = Long.MAX_VALUE;
        List<Long> ids = logs.getMessages().stream()
                .map(LogEntriesEntity.Message::getId)
                .collect(Collectors.toList());
        for (final Long id : ids) {
            assertThat(lastId, greaterThan(id));
            lastId = id;
        }
    }

    @Test
    public void getLogLevel() {
        assumeTrue(isAtLeastVersion(3, 7)); // it fails in 3.6 active-failover (BTS-362)
        final LogLevelEntity logLevel = arangoDB.getLogLevel();
        assertThat(logLevel, is(notNullValue()));
        assertThat(logLevel.getAgency(), is(LogLevelEntity.LogLevel.INFO));
    }

    @Test
    public void setLogLevel() {
        assumeTrue(isAtLeastVersion(3, 7)); // it fails in 3.6 active-failover (BTS-362)
        final LogLevelEntity entity = new LogLevelEntity();
        try {
            entity.setAgency(LogLevelEntity.LogLevel.ERROR);
            final LogLevelEntity logLevel = arangoDB.setLogLevel(entity);
            assertThat(logLevel, is(notNullValue()));
            assertThat(logLevel.getAgency(), is(LogLevelEntity.LogLevel.ERROR));
        } finally {
            entity.setAgency(LogLevelEntity.LogLevel.INFO);
            arangoDB.setLogLevel(entity);
        }
    }

    @Test
    public void setAllLogLevel() {
        assumeTrue(isAtLeastVersion(3, 9));
        final LogLevelEntity entity = new LogLevelEntity();
        try {
            entity.setAll(LogLevelEntity.LogLevel.ERROR);
            final LogLevelEntity logLevel = arangoDB.setLogLevel(entity);
            assertThat(logLevel, is(notNullValue()));
            assertThat(logLevel.getAgency(), is(LogLevelEntity.LogLevel.ERROR));
            assertThat(logLevel.getAgency(), is(LogLevelEntity.LogLevel.ERROR));

            LogLevelEntity retrievedLevels = arangoDB.getLogLevel();
            assertThat(retrievedLevels.getAgency(), is(LogLevelEntity.LogLevel.ERROR));
            assertThat(retrievedLevels.getAgency(), is(LogLevelEntity.LogLevel.ERROR));

            assertThat(logLevel.getAgency(), is(LogLevelEntity.LogLevel.ERROR));
            assertThat(logLevel.getQueries(), is(LogLevelEntity.LogLevel.ERROR));
        } finally {
            entity.setAll(LogLevelEntity.LogLevel.INFO);
            arangoDB.setLogLevel(entity);
        }
    }

    @Test
    public void arangoDBException() {
        try {
            arangoDB.db(DbName.of("no")).getInfo();
            fail();
        } catch (final ArangoDBException e) {
            assertThat(e.getResponseCode(), is(404));
            assertThat(e.getErrorNum(), is(1228));
        }
    }

    @Test
    public void fallbackHost() {
        final ArangoDB arangoDB = new ArangoDB.Builder().host("not-accessible", 8529).host("127.0.0.1", 8529).build();
        final ArangoDBVersion version = arangoDB.getVersion();
        assertThat(version, is(notNullValue()));
    }

    @Test(expected = ArangoDBException.class)
    public void loadproperties() {
        new ArangoDB.Builder().loadProperties(ArangoDBTest.class.getResourceAsStream("/arangodb-bad.properties"));
    }

    @Test(expected = ArangoDBException.class)
    public void loadproperties2() {
        new ArangoDB.Builder().loadProperties(ArangoDBTest.class.getResourceAsStream("/arangodb-bad2.properties"));
    }

    @Test
    public void accessMultipleDatabases() {
        final ArangoDBVersion version1 = db1.getVersion();
        assertThat(version1, is(notNullValue()));
        final ArangoDBVersion version2 = db2.getVersion();
        assertThat(version2, is(notNullValue()));
    }

    @Test
    public void queueTime() throws InterruptedException, ExecutionException {
        List<CompletableFuture<Void>> futures = IntStream.range(0, 80)
                .mapToObj(i -> CompletableFuture.runAsync(
                        () -> arangoDB.db().query("RETURN SLEEP(1)", Void.class),
                        Executors.newFixedThreadPool(80))
                )
                .collect(Collectors.toList());
        for (CompletableFuture<Void> f : futures) {
            f.get();
        }

        QueueTimeMetrics qt = arangoDB.metrics().getQueueTime();
        double avg = qt.getAvg();
        QueueTimeSample[] values = qt.getValues();
        if (isAtLeastVersion(3, 9)) {
            assertThat(values.length, is(20));
            for (int i = 0; i < values.length; i++) {
                assertThat(values[i], is(notNullValue()));
                assertThat(values[i].value, is(greaterThanOrEqualTo(0.0)));
                if (i > 0) {
                    assertThat(values[i].timestamp, greaterThanOrEqualTo(values[i - 1].timestamp));
                }
            }
            assertThat(avg, is(greaterThan(0.0)));
        } else {
            assertThat(avg, is(0.0));
            assertThat(values, is(emptyArray()));
        }

    }
}
