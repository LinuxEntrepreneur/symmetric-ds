package org.jumpmind.symmetric.route;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.jumpmind.db.platform.DatabaseInfo;
import org.jumpmind.db.platform.IDatabasePlatform;
import org.jumpmind.db.sql.ISqlRowMapper;
import org.jumpmind.db.sql.ISqlTemplate;
import org.jumpmind.db.sql.ISqlTransaction;
import org.jumpmind.symmetric.AbstractSymmetricEngine;
import org.jumpmind.symmetric.ISymmetricEngine;
import org.jumpmind.symmetric.common.ContextConstants;
import org.jumpmind.symmetric.common.ParameterConstants;
import org.jumpmind.symmetric.db.AbstractSymmetricDialect;
import org.jumpmind.symmetric.db.ISymmetricDialect;
import org.jumpmind.symmetric.model.DataGap;
import org.jumpmind.symmetric.model.Node;
import org.jumpmind.symmetric.model.ProcessInfo;
import org.jumpmind.symmetric.model.ProcessInfoKey;
import org.jumpmind.symmetric.service.IContextService;
import org.jumpmind.symmetric.service.IDataService;
import org.jumpmind.symmetric.service.IExtensionService;
import org.jumpmind.symmetric.service.INodeService;
import org.jumpmind.symmetric.service.IParameterService;
import org.jumpmind.symmetric.service.IRouterService;
import org.jumpmind.symmetric.service.impl.ContextService;
import org.jumpmind.symmetric.service.impl.DataService;
import org.jumpmind.symmetric.service.impl.ExtensionService;
import org.jumpmind.symmetric.service.impl.NodeService;
import org.jumpmind.symmetric.service.impl.ParameterService;
import org.jumpmind.symmetric.service.impl.RouterService;
import org.jumpmind.symmetric.statistic.IStatisticManager;
import org.jumpmind.symmetric.statistic.StatisticManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Matchers;
import org.mockito.Mockito;
import org.mockito.internal.verification.VerificationModeFactory;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

public class DataGapDetectorTest {

    final static String ENGINE_NAME = "testengine";
    final static String CHANNEL_ID = "testchannel";
    final static String NODE_ID = "00000";
    final static String NODE_GROUP_ID = "testgroup";

    ISqlTemplate sqlTemplate;
    ISqlTransaction sqlTransaction;
    IDataService dataService;
    IParameterService parameterService;
    IContextService contextService;
    ISymmetricDialect symmetricDialect;
    IRouterService routerService;
    IStatisticManager statisticManager;
    INodeService nodeService;

    @Before
    public void setUp() throws Exception {
        sqlTemplate = mock(ISqlTemplate.class);
        sqlTransaction = mock(ISqlTransaction.class); 
        when(sqlTemplate.startSqlTransaction()).thenReturn(sqlTransaction);
        IDatabasePlatform platform = mock(IDatabasePlatform.class);
        when(platform.getDatabaseInfo()).thenReturn(new DatabaseInfo());
        when(platform.getSqlTemplate()).thenReturn(sqlTemplate);
        symmetricDialect = mock(AbstractSymmetricDialect.class);
        when(symmetricDialect.getPlatform()).thenReturn(platform);
        when(symmetricDialect.supportsTransactionViews()).thenReturn(false);
        when(symmetricDialect.getDatabaseTime()).thenReturn(0L);

        parameterService = mock(ParameterService.class);
        when(parameterService.getEngineName()).thenReturn(ENGINE_NAME);
        when(parameterService.getLong(ParameterConstants.ROUTING_STALE_DATA_ID_GAP_TIME)).thenReturn(60000L);
        when(parameterService.getInt(ParameterConstants.DATA_ID_INCREMENT_BY)).thenReturn(1);
        when(parameterService.getLong(ParameterConstants.ROUTING_LARGEST_GAP_SIZE)).thenReturn(50000000L);
        when(parameterService.getLong(ParameterConstants.DBDIALECT_ORACLE_TRANSACTION_VIEW_CLOCK_SYNC_THRESHOLD_MS)).thenReturn(60000L);
        when(parameterService.getLong(ParameterConstants.ROUTING_STALE_GAP_BUSY_EXPIRE_TIME)).thenReturn(60000L);

        IExtensionService extensionService = mock(ExtensionService.class);
        ISymmetricEngine engine = mock(AbstractSymmetricEngine.class);
        when(engine.getParameterService()).thenReturn(parameterService);
        when(engine.getStatisticManager()).thenReturn(statisticManager);
        when(engine.getNodeService()).thenReturn(nodeService);
        when(engine.getDataService()).thenReturn(dataService);
        when(engine.getSymmetricDialect()).thenReturn(symmetricDialect);
        when(engine.getExtensionService()).thenReturn(extensionService);
        routerService = new RouterService(engine);
        when(engine.getRouterService()).thenReturn(routerService);

        contextService = mock(ContextService.class);
        dataService = mock(DataService.class);
        
        statisticManager = mock(StatisticManager.class);
        when(statisticManager.newProcessInfo((ProcessInfoKey) any())).thenReturn(new ProcessInfo());
        
        nodeService = mock(NodeService.class);
        when(nodeService.findIdentity()).thenReturn(new Node(NODE_ID, NODE_GROUP_ID));
    }

    protected DataGapFastDetector newGapDetector() {
        return new DataGapFastDetector(dataService, parameterService, contextService, symmetricDialect, routerService, statisticManager, nodeService);
    }

    protected void runGapDetector(List<DataGap> dataGaps, List<Long> dataIds, boolean isAllDataRead) {
        when(dataService.findDataGaps()).thenReturn(dataGaps);
        DataGapFastDetector detector = newGapDetector();
        detector.beforeRouting();
        detector.addDataIds(dataIds);
        detector.setIsAllDataRead(isAllDataRead);
        detector.afterRouting();
    }

    protected void runGapDetector(final List<DataGap> dataGaps1, final List<DataGap> dataGaps2, List<Long> dataIds, boolean isAllDataRead) {
        when(dataService.findDataGaps()).thenAnswer(new Answer<List<DataGap>>() {
            int i;
            public List<DataGap> answer(InvocationOnMock invocation) {
                return i++ == 0 ? dataGaps1 : dataGaps2;
            }
        });

        DataGapFastDetector detector = newGapDetector();
        detector.beforeRouting();
        detector.addDataIds(dataIds);
        detector.setIsAllDataRead(isAllDataRead);
        detector.afterRouting();
    }

    @Test
    public void testNewGap() throws Exception {
        List<DataGap> dataGaps = new ArrayList<DataGap>();
        dataGaps.add(new DataGap(3, 3));
        dataGaps.add(new DataGap(4, 50000004));
        
        List<Long> dataIds = new ArrayList<Long>();
        dataIds.add(100L);

        runGapDetector(dataGaps, dataIds, true);

        verify(dataService).findDataGaps();
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(4, 50000004));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(4, 99));        
        verify(dataService).insertDataGap(new DataGap(101, 50000100));
        verifyNoMoreInteractions(dataService);
    }

    @Test
    public void testNewGapFull() throws Exception {
        when(contextService.is(ContextConstants.ROUTING_FULL_GAP_ANALYSIS)).thenReturn(true);

        List<Long> dataIds = new ArrayList<Long>();
        dataIds.add(100L);

        @SuppressWarnings("unchecked")
        ISqlRowMapper<Long> mapper = (ISqlRowMapper<Long>) Matchers.anyObject();
        String sql = Matchers.anyString();
        when(sqlTemplate.query(sql, mapper, Matchers.eq(4L), Matchers.eq(50000004L))).thenReturn(dataIds);
        
        List<DataGap> dataGaps = new ArrayList<DataGap>();
        dataGaps.add(new DataGap(3, 3));
        dataGaps.add(new DataGap(4, 50000004));

        runGapDetector(dataGaps, new ArrayList<Long>(), true);

        verify(dataService, VerificationModeFactory.times(2)).findDataGaps();
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(4, 50000004));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(4, 99));
        verify(dataService).insertDataGap(new DataGap(101, 50000100));
        verifyNoMoreInteractions(dataService);
    }
    
    @Test
    public void testTwoNewGaps() throws Exception {
        List<DataGap> dataGaps = new ArrayList<DataGap>();
        dataGaps.add(new DataGap(3, 3));
        dataGaps.add(new DataGap(4, 50000004));
        
        List<Long> dataIds = new ArrayList<Long>();
        dataIds.add(5L);
        dataIds.add(8L);

        runGapDetector(dataGaps, dataIds, true);

        verify(dataService).findDataGaps();
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(4, 50000004));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(4, 4));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(6, 7));
        verify(dataService).insertDataGap(new DataGap(9, 50000008));
        verifyNoMoreInteractions(dataService);
    }

    @Test
    public void testTwoNewGapsFull() throws Exception {
        when(contextService.is(ContextConstants.ROUTING_FULL_GAP_ANALYSIS)).thenReturn(true);

        List<Long> dataIds = new ArrayList<Long>();
        dataIds.add(5L);
        dataIds.add(8L);

        @SuppressWarnings("unchecked")
        ISqlRowMapper<Long> mapper = (ISqlRowMapper<Long>) Matchers.anyObject();
        String sql = Matchers.anyString();
        when(sqlTemplate.query(sql, mapper, Matchers.eq(4L), Matchers.eq(50000004L))).thenReturn(dataIds);

        List<DataGap> dataGaps = new ArrayList<DataGap>();
        dataGaps.add(new DataGap(3, 3));
        dataGaps.add(new DataGap(4, 50000004));
        
        runGapDetector(dataGaps, new ArrayList<Long>(), true);

        verify(dataService, VerificationModeFactory.times(2)).findDataGaps();
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(4, 50000004));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(4, 4));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(6, 7));
        verify(dataService).insertDataGap(new DataGap(9, 50000008));
        verifyNoMoreInteractions(dataService);
    }

    @Test
    public void testGapInGap() throws Exception {
        List<DataGap> dataGaps = new ArrayList<DataGap>();
        dataGaps.add(new DataGap(3, 3));
        dataGaps.add(new DataGap(5, 10));
        dataGaps.add(new DataGap(15, 20));
        dataGaps.add(new DataGap(21, 50000020));
        
        List<Long> dataIds = new ArrayList<Long>();
        dataIds.add(6L);
        dataIds.add(18L);
        dataIds.add(23L);

        runGapDetector(dataGaps, dataIds, true);

        verify(dataService).findDataGaps();
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(5, 10));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(5, 5));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(7, 10));
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(15, 20));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(15, 17));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(19, 20));        
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(21, 50000020));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(21, 22));
        verify(dataService).insertDataGap(new DataGap(24, 50000023));
        verifyNoMoreInteractions(dataService);
    }

    @Test
    public void testGapInGapFull() throws Exception {        
        when(contextService.is(ContextConstants.ROUTING_FULL_GAP_ANALYSIS)).thenReturn(true);

        @SuppressWarnings("unchecked")
        ISqlRowMapper<Long> mapper = (ISqlRowMapper<Long>) Matchers.anyObject();
        when(sqlTemplate.query(Matchers.anyString(), mapper, Matchers.anyVararg())).thenAnswer(new Answer<List<Long>>() {
            public List<Long> answer(InvocationOnMock invocation) {
                List<Long> dataIds = new ArrayList<Long>();
                long startId = (Long) invocation.getArguments()[2];
                long endId = (Long) invocation.getArguments()[3];
                if (startId == 5 && endId == 10) {
                    dataIds.add(6L);                    
                } else if (startId == 15 && endId == 20) {
                    dataIds.add(18L);
                } else if (startId == 21 && endId == 50000020) {
                    dataIds.add(23L);
                }
                return dataIds;
            }
        });

        List<DataGap> dataGaps = new ArrayList<DataGap>();
        dataGaps.add(new DataGap(3, 3));
        dataGaps.add(new DataGap(5, 10));
        dataGaps.add(new DataGap(15, 20));
        dataGaps.add(new DataGap(21, 50000020));

        runGapDetector(dataGaps, new ArrayList<Long>(), true);

        verify(dataService, VerificationModeFactory.times(2)).findDataGaps();
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(5, 10));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(5, 5));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(7, 10));
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(15, 20));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(15, 17));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(19, 20));        
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(21, 50000020));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(21, 22));
        verify(dataService).insertDataGap(new DataGap(24, 50000023));
        verifyNoMoreInteractions(dataService);
    }

    @Test
    public void testGapExpire() throws Exception {
        List<DataGap> dataGaps = new ArrayList<DataGap>();
        dataGaps.add(new DataGap(3, 3));
        dataGaps.add(new DataGap(5, 6));
        dataGaps.add(new DataGap(7, 50000006));

        when(symmetricDialect.getDatabaseTime()).thenReturn(System.currentTimeMillis() + 60001L);
        runGapDetector(dataGaps, new ArrayList<Long>(), true);

        verify(dataService).findDataGaps();
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(3, 3));
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(5, 6));
        verifyNoMoreInteractions(dataService);
    }

    @Test
    public void testGapExpireBusyChannel() throws Exception {
        List<DataGap> dataGaps = new ArrayList<DataGap>();
        dataGaps.add(new DataGap(3, 3));
        dataGaps.add(new DataGap(5, 6));
        dataGaps.add(new DataGap(7, 50000006));
        
        when(symmetricDialect.getDatabaseTime()).thenReturn(System.currentTimeMillis() + 60001L);
        when(dataService.countDataInRange(4, 7)).thenReturn(1);
        runGapDetector(dataGaps, new ArrayList<Long>(), false);

        verify(dataService).findDataGaps();
        verify(dataService).countDataInRange(2, 4);
        verify(dataService).countDataInRange(4, 7);
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(3, 3));
        verifyNoMoreInteractions(dataService);
    }

    @Test
    public void testGapBusyExpireRun() throws Exception {
        List<DataGap> dataGaps = new ArrayList<DataGap>();
        dataGaps.add(new DataGap(3, 3));
        dataGaps.add(new DataGap(5, 6));
        dataGaps.add(new DataGap(7, 50000006));

        when(symmetricDialect.getDatabaseTime()).thenReturn(System.currentTimeMillis() + 60001L);
        when(contextService.getLong(ContextConstants.ROUTING_LAST_BUSY_EXPIRE_RUN_TIME)).thenReturn(System.currentTimeMillis() - 61000);
        runGapDetector(dataGaps, new ArrayList<Long>(), false);

        verify(dataService).findDataGaps();
        verify(dataService).countDataInRange(2, 4);
        verify(dataService).countDataInRange(4, 7);
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(3, 3));
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(5, 6));
        verifyNoMoreInteractions(dataService);
    }

    @Test
    public void testGapBusyExpireNoRun() throws Exception {
        List<DataGap> dataGaps = new ArrayList<DataGap>();
        dataGaps.add(new DataGap(3, 3));
        dataGaps.add(new DataGap(5, 6));
        dataGaps.add(new DataGap(7, 50000006));

        when(symmetricDialect.getDatabaseTime()).thenReturn(System.currentTimeMillis() + 60001L);
        when(contextService.getLong(ContextConstants.ROUTING_LAST_BUSY_EXPIRE_RUN_TIME)).thenReturn(System.currentTimeMillis());
        runGapDetector(dataGaps, new ArrayList<Long>(), false);

        verify(dataService).findDataGaps();
        verifyNoMoreInteractions(dataService);
    }

    @Test
    public void testGapExpireOracle() throws Exception {
        List<DataGap> dataGaps = new ArrayList<DataGap>();
        dataGaps.add(new DataGap(3, 3));
        dataGaps.add(new DataGap(5, 6));
        dataGaps.add(new DataGap(7, 50000006));
        
        when(symmetricDialect.supportsTransactionViews()).thenReturn(true);
        when(symmetricDialect.getEarliestTransactionStartTime()).thenReturn(new Date(System.currentTimeMillis() + 60001L));
        runGapDetector(dataGaps, new ArrayList<Long>(), true);

        verify(dataService).findDataGaps();
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(3, 3));
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(5, 6));
        verifyNoMoreInteractions(dataService);
    }

    @Test
    public void testGapExpireOracleBusyChannel() throws Exception {
        List<DataGap> dataGaps = new ArrayList<DataGap>();
        dataGaps.add(new DataGap(3, 3));
        dataGaps.add(new DataGap(5, 6));
        dataGaps.add(new DataGap(7, 50000006));

        when(symmetricDialect.supportsTransactionViews()).thenReturn(true);
        when(symmetricDialect.getEarliestTransactionStartTime()).thenReturn(new Date(System.currentTimeMillis() + 60001L));
        when(dataService.countDataInRange(4, 7)).thenReturn(1);
        runGapDetector(dataGaps, new ArrayList<Long>(), false);

        verify(dataService).findDataGaps();
        verify(dataService).countDataInRange(2, 4);
        verify(dataService).countDataInRange(4, 7);
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(3, 3));
        verifyNoMoreInteractions(dataService);
    }

    @Test
    public void testGapsBeforeAndAfterFull() throws Exception {
        when(contextService.is(ContextConstants.ROUTING_FULL_GAP_ANALYSIS)).thenReturn(true);
        List<Long> dataIds = new ArrayList<Long>();
        dataIds.add(843L);
        dataIds.add(844L);

        @SuppressWarnings("unchecked")
        ISqlRowMapper<Long> mapper = (ISqlRowMapper<Long>) Matchers.anyObject();
        String sql = Matchers.anyString();
        when(sqlTemplate.query(sql, mapper, Matchers.eq(841L), Matchers.eq(50000840L))).thenReturn(dataIds);

        List<DataGap> dataGaps1 = new ArrayList<DataGap>();
        dataGaps1.add(new DataGap(841, 50000840));

        List<DataGap> dataGaps2 = new ArrayList<DataGap>();
        dataGaps2.add(new DataGap(841, 842));
        dataGaps2.add(new DataGap(845, 50000844));

        dataIds = new ArrayList<Long>();
        dataIds.add(845L);
        
        runGapDetector(dataGaps1, dataGaps2, dataIds, true);

        verify(dataService, VerificationModeFactory.times(2)).findDataGaps();
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(841, 50000840));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(841, 842));
        verify(dataService).insertDataGap(new DataGap(845, 50000844));
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(845, 50000844));
        verify(dataService).insertDataGap(new DataGap(846, 50000845));
        verifyNoMoreInteractions(dataService);
    }

    // Commenting out the overlap tests because these would need to be handled by
    // a repair routine that runs at the beginning of the gap detection.

    //@Test
    public void testGapsOverlap() throws Exception {
        List<Long> dataIds = new ArrayList<Long>();

        List<DataGap> dataGaps = new ArrayList<DataGap>();
        dataGaps.add(new DataGap(30953883, 80953883));
        dataGaps.add(new DataGap(30953884, 80953883));
        
        runGapDetector(dataGaps, dataIds, true);

        verify(dataService).findDataGaps();
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(30953884, 80953883));
        verifyNoMoreInteractions(dataService);
    }

    //@Test
    public void testGapsOverlapMultiple() throws Exception {
        List<Long> dataIds = new ArrayList<Long>();

        List<DataGap> dataGaps = new ArrayList<DataGap>();
        dataGaps.add(new DataGap(1, 10));
        dataGaps.add(new DataGap(3, 8));
        dataGaps.add(new DataGap(4, 6));
        dataGaps.add(new DataGap(4, 8));
        dataGaps.add(new DataGap(4, 5));
        dataGaps.add(new DataGap(5, 10));
        dataGaps.add(new DataGap(6, 11));
        
        runGapDetector(dataGaps, dataIds, true);

        verify(dataService).findDataGaps();
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(1, 10));
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(3, 8));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(1, 10));
        
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(1, 10));
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(4, 6));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(1, 10));

        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(1, 10));
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(4, 8));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(1, 10));

        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(1, 10));
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(4, 5));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(1, 10));

        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(1, 10));
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(5, 10));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(1, 10));

        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(1, 10));
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(6, 11));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(1, 11));

        verifyNoMoreInteractions(dataService);
    }

    //@Test
    public void testGapsOverlapThenData() throws Exception {
        List<Long> dataIds = new ArrayList<Long>();
        dataIds.add(30953883L);

        List<DataGap> dataGaps = new ArrayList<DataGap>();
        dataGaps.add(new DataGap(30953883, 80953883));
        dataGaps.add(new DataGap(30953884, 80953883));
        
        runGapDetector(dataGaps, dataIds, true);

        verify(dataService).findDataGaps();
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(30953884, 80953883));
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(30953883, 80953883));
        verify(dataService).insertDataGap(new DataGap(30953884, 80953883));
        verifyNoMoreInteractions(dataService);
    }

    //@Test
    public void testGapsOverlapThenDataFull() throws Exception {
        when(contextService.is(ContextConstants.ROUTING_FULL_GAP_ANALYSIS)).thenReturn(true);
        List<Long> dataIds = new ArrayList<Long>();
        dataIds.add(30953883L);

        @SuppressWarnings("unchecked")
        ISqlRowMapper<Long> mapper = (ISqlRowMapper<Long>) Matchers.anyObject();
        String sql = Matchers.anyString();
        when(sqlTemplate.query(sql, mapper, Matchers.eq(30953883L), Matchers.eq(80953883L))).thenReturn(dataIds);

        List<DataGap> dataGaps1 = new ArrayList<DataGap>();
        dataGaps1.add(new DataGap(30953883, 80953883));
        dataGaps1.add(new DataGap(30953884, 80953883));

        List<DataGap> dataGaps2 = new ArrayList<DataGap>();
        dataGaps2.add(new DataGap(30953884, 80953883));

        dataIds = new ArrayList<Long>();
        dataIds.add(30953883L);
        
        runGapDetector(dataGaps1, dataGaps2, dataIds, true);

        verify(dataService, VerificationModeFactory.times(2)).findDataGaps();
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(30953884, 80953883));
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(30953883, 80953883));
        verify(dataService).insertDataGap(new DataGap(30953884, 80953883));
        verifyNoMoreInteractions(dataService);
    }

    //@Test
    public void testGapsOverlapAfterLastGap() throws Exception {
        List<Long> dataIds = new ArrayList<Long>();
        dataIds.add(30953883L);

        List<DataGap> dataGaps = new ArrayList<DataGap>();
        dataGaps.add(new DataGap(30953883, 80953883));
        dataGaps.add(new DataGap(30953884, 80953883));
        dataGaps.add(new DataGap(30953885, 81953883));
        dataGaps.add(new DataGap(30953885, 30953885));
        
        runGapDetector(dataGaps, dataIds, true);

        verify(dataService).findDataGaps();
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(30953884, 80953883));
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(30953885, 81953883));
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(30953885, 30953885));
        
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(30953883, 80953883));
        verify(dataService).insertDataGap(new DataGap(30953884, 80953883));
        verifyNoMoreInteractions(dataService);
    }

    @Test
    public void testGapsDuplicateDetection() throws Exception {
        List<Long> dataIds = new ArrayList<Long>();
        dataIds.add(31832439L);

        List<DataGap> dataGaps = new ArrayList<DataGap>();
        dataGaps.add(new DataGap(31832006, 31832438));
        dataGaps.add(new DataGap(31832439, 81832439));
        dataGaps.add(new DataGap(31832440, 81832439));
        
        runGapDetector(dataGaps, dataIds, true);

        verify(dataService).findDataGaps();
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(31832439, 81832439));
        verifyNoMoreInteractions(dataService);
    }

    @Test
    public void testGapsOverlapDetection() throws Exception {
        List<Long> dataIds = new ArrayList<Long>();
        dataIds.add(31837983L);
        dataIds.add(31837989L);

        List<DataGap> dataGaps = new ArrayList<DataGap>();
        dataGaps.add(new DataGap(31837983, 81837982));
        dataGaps.add(new DataGap(31837983, 81837983));
        
        runGapDetector(dataGaps, dataIds, true);

        verify(dataService).findDataGaps();
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(31837983, 81837982));
        verify(dataService).deleteDataGap(sqlTransaction, new DataGap(31837983, 81837983));
        verify(dataService).insertDataGap(sqlTransaction, new DataGap(31837984, 31837988));
        verify(dataService).insertDataGap(new DataGap(31837990, 81837989));
        verifyNoMoreInteractions(dataService);
    }

    @Test
    public void testRandom() throws Exception {
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        for (int loop = 0; loop < 5000; loop++) {
            List<DataGap> dataGaps = new ArrayList<DataGap>();
            long startId = 0, endId = 0;
            for (int i = 0; i < rand.nextInt(1, 10); i++) {
                startId = rand.nextLong(endId + 1, endId + 10);
                endId = rand.nextLong(startId, startId + 10);
                dataGaps.add(new DataGap(startId, endId));    
            }
            
            List<Long> dataIds = new ArrayList<Long>();
            for (DataGap dataGap : dataGaps) {
                if (rand.nextBoolean()) {
                    for (long dataId = dataGap.getStartId(); dataId <= dataGap.getEndId(); dataId++) {
                        if (rand.nextBoolean()) {
                            dataIds.add(dataId);
                        }
                    }
                }
            }

            final Set<DataGap> allGaps = new HashSet<DataGap>(dataGaps);
            doAnswer(new Answer<Void>() {
                public Void answer(InvocationOnMock invocation) {
                  Object[] args = invocation.getArguments();
                  DataGap gap = (DataGap) args[1];
                  checkDeleteGap(allGaps, gap);
                  return null;
                }
            }).when(dataService).deleteDataGap(Matchers.eq(sqlTransaction), (DataGap) Matchers.anyObject());

            doAnswer(new Answer<Void>() {
                public Void answer(InvocationOnMock invocation) {
                  Object[] args = invocation.getArguments();
                  DataGap gap = (DataGap) args[1];
                  checkInsertGap(allGaps, gap);
                  return null;
                }
            }).when(dataService).insertDataGap(Matchers.eq(sqlTransaction), (DataGap) Matchers.anyObject());

            doAnswer(new Answer<Void>() {
                public Void answer(InvocationOnMock invocation) {
                  Object[] args = invocation.getArguments();
                  DataGap gap = (DataGap) args[0];
                  checkInsertGap(allGaps, gap);
                  return null;
                }
            }).when(dataService).insertDataGap((DataGap) Matchers.anyObject());

            runGapDetector(dataGaps, dataIds, true);
    
            verify(dataService).findDataGaps();

            int index = 0;
            int lastIndex = dataGaps.size() - 1;
            boolean isLastGapInserted = false;
            boolean isLastGapModified = false;
            for (DataGap dataGap : dataGaps) {
                long lastDataId = -1;
                for (Long dataId : dataIds) {
                    if (dataId >= dataGap.getStartId() && dataId <= dataGap.getEndId()) {
                        if (lastDataId == -1) {
                            verify(dataService).deleteDataGap(sqlTransaction, dataGap);
                            if (dataId > dataGap.getStartId()) {
                                verify(dataService).insertDataGap(sqlTransaction, new DataGap(dataGap.getStartId(), dataId - 1));    
                            }
                            if (index == lastIndex) {
                                isLastGapModified = true;
                            }
                        } else if (dataId > lastDataId + 1) {
                            verify(dataService).insertDataGap(sqlTransaction, new DataGap(lastDataId + 1, dataId - 1));
                        }
                        lastDataId = dataId;
                    } else if (dataId > dataGap.getEndId()){
                        break;
                    }
                }
                if (lastDataId >= dataGap.getStartId() && lastDataId < dataGap.getEndId()) {
                    if (index == lastIndex) {
                        isLastGapInserted = true;
                        verify(dataService).insertDataGap(new DataGap(lastDataId + 1, lastDataId + 50000000));                        
                    } else {
                        verify(dataService).insertDataGap(sqlTransaction, new DataGap(lastDataId + 1, dataGap.getEndId()));
                    }
                }
                index++;
            }
            
            if (!isLastGapInserted && isLastGapModified) {
                DataGap lastGap = dataGaps.get(lastIndex);
                if (lastGap.getEndId() - lastGap.getStartId() < 50000000 - 1) {
                    verify(dataService).insertDataGap(new DataGap(lastGap.getEndId() + 1, lastGap.getEndId() + 50000000));                        
                }
            }

            verifyNoMoreInteractions(dataService);
            Mockito.reset(dataService);
        }
    }
    
    private void checkInsertGap(Set<DataGap> allGaps, DataGap gap) {
        checkGap(allGaps, gap);
        if (allGaps.contains(gap)) {
            throw new RuntimeException("Detected a duplicate data gap: " + gap);
        }
        for (DataGap gapAdded : allGaps) {
            if (gap.overlaps(gapAdded)) {
                throw new RuntimeException("Detected an overlapping data gap: " + gap);
            }
        }
        allGaps.add(gap);
    }

    private void checkDeleteGap(Set<DataGap> allGaps, DataGap gap) {
        checkGap(allGaps, gap);
        if (!allGaps.contains(gap)) {
            throw new RuntimeException("Detected a delete of a non-existent data gap: " + gap);
        }
        allGaps.remove(gap);
    }

    private void checkGap(Set<DataGap> allGaps, DataGap gap) {
        if (gap.getStartId() > gap.getEndId()) {
            throw new RuntimeException("Detected an invalid gap range: " + gap);
        } else if (gap.gapSize() < 50000000 - 1 && gap.gapSize() >= (long) (50000000 * 0.75)) {
            throw new RuntimeException("Detected a very large gap range: " + gap);
        }
    }

}
