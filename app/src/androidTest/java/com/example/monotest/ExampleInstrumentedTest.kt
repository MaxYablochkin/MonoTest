package com.example.monotest

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.monotest.data.api.FakeStatementApi
import com.example.monotest.database.AppDatabase
import com.example.monotest.domain.StatementRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun roomDaoAndRepository_maintainContinuousWindowAndIntersection() = runBlocking {
        val cards = listOf("card_black", "card_white")
        val fake = FakeStatementApi(cards = cards, initialPerCard = 25, latencyMs = 0L, seed = 42L)
        val dao = db.dao()
        val repo = StatementRepository(api = fake, dao = dao, cards = cards, pageSize = 20)

        repo.refreshAll()

        assertEquals(20, dao.rowsOf("card_black").size)
        assertEquals(20, dao.rowsOf("card_white").size)

        fake.appendNew("card_black", 45)
        repo.refresh("card_black")
        assertEquals(65, dao.rowsOf("card_black").size)

        // Refresh white card so unified window advances to latest asOfSeq
        repo.refresh("card_white")
        val allWindow = repo.observeAll().first()
        val syncBlack = dao.sync("card_black")!!
        val syncWhite = dao.sync("card_white")!!
        val expectedLo = maxOf(syncBlack.oldestSeq, syncWhite.oldestSeq)
        val expectedHi = minOf(syncBlack.newestSeq, syncWhite.newestSeq)

        assertEquals(
            (fake.items("card_black") + fake.items("card_white"))
                .filter { it.seq in expectedLo..expectedHi }
                .map { it.seq }
                .sortedDescending(),
            allWindow.map { it.seq }
        )
    }
}
