/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.advancedcoroutines

import androidx.annotation.AnyThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.map
import androidx.lifecycle.switchMap
import com.example.android.advancedcoroutines.util.CacheOnSuccess
import com.example.android.advancedcoroutines.utils.ComparablePair
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

/**
 * Repository module for handling data operations.
 *
 * This PlantRepository exposes two UI-observable database queries [plants] and
 * [getPlantsWithGrowZone].
 *
 * To update the plants cache, call [tryUpdateRecentPlantsForGrowZoneCache] or
 * [tryUpdateRecentPlantsCache].
 */
class PlantRepository private constructor(
    private val plantDao: PlantDao,
    private val plantService: NetworkService,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    /**
     * Fetch a list of [Plant]s from the database.
     * Returns a LiveData-wrapped List of Plants.
     */
    val plants: LiveData<List<Plant>> = /*plantDao.getPlants()*/
        liveData<List<Plant>> {
            val plantsLiveData = plantDao.getPlants()
            val customSortOder = plantsListSortOrderCache.getOrAwait()

            emitSource(plantsLiveData.map {
                plantList -> plantList.applySort(customSortOder)
            })
        }

    private var plantsListSortOrderCache =
        CacheOnSuccess(onErrorFallback = { listOf() }) {
            plantService.customPlantSortOrder()
    }

    val customSortFlow = flow { emit(plantsListSortOrderCache.getOrAwait()) }/*.onStart {
        emit(listOf())
        delay(1500)
    }*/
    // optional
    //val customSortFlow = plantsListSortOrderCache::getOrAwait.asFlow()

    val plantsFlow: Flow<List<Plant>>
        get() = plantDao.getPlantsFlow() // When the result of customSortFlow is available,
            // this will combine it with the latest value from
            // the flow above.  Thus, as long as both `plants`
            // and `sortOrder` are have an initial value (their
            // flow has emitted at least one value), any change
            // to either `plants` or `sortOrder`  will call
            // `plants.applySort(sortOrder)`.
            .combine(customSortFlow) { plants, sortOrder ->
                plants.applySort(sortOrder)
            }
            //    The operator flowOn launches a new coroutine to collect the flow above it and introduces a buffer to write the results.
            //    You can control the buffer with more operators, such as conflate which says to store only the last value produced in the buffer.
            /**    It's important to be aware of the buffer when using flowOn with large objects such as Room results since it is easy to use a large amount of memory buffering results.*/
            .flowOn(defaultDispatcher).conflate()


    /**
     * Fetch a list of [Plant]s from the database that matches a given [GrowZone].
     * Returns a LiveData-wrapped List of Plants.
     */
    fun getPlantsWithGrowZone(growZone: GrowZone): LiveData<List<Plant>> =
        /*plantDao.getPlantsWithGrowZoneNumber(growZone.number)*/
        //---------------------------------------------------------
        /*liveData<List<Plant>> {
            val plantsGrowZoneLiveData = plantDao.getPlantsWithGrowZoneNumber(growZone.number)
            val customSortOrder = plantsListSortOrderCache.getOrAwait()

            emitSource(plantsGrowZoneLiveData.map { plantList ->
                plantList.applySort(customSortOrder)
            })
        }*/
        //----------------------------------------------------------
        plantDao.getPlantsWithGrowZoneNumber(growZone.number)
            .switchMap { plantList ->
                liveData {
                    val customSortOrder = plantsListSortOrderCache.getOrAwait()
                    emit(plantList.applyMainSafeSort(customSortOrder))
                }
            }

    fun getPlantsWithGrowZoneFlow(growZone: GrowZone): Flow<List<Plant>> {
        return plantDao.getPlantsWithGrowZoneNumberFlow(growZoneNumber = growZone.number)
    }

    /**
     * Returns true if we should make a network request.
     */
    private fun shouldUpdatePlantsCache(): Boolean {
        // suspending function, so you can e.g. check the status of the database here
        return true
    }

    /**
     * Update the plants cache.
     *
     * This function may decide to avoid making a network requests on every call based on a
     * cache-invalidation policy.
     */
    suspend fun tryUpdateRecentPlantsCache() {
        if (shouldUpdatePlantsCache()) fetchRecentPlants()
    }

    /**
     * Update the plants cache for a specific grow zone.
     *
     * This function may decide to avoid making a network requests on every call based on a
     * cache-invalidation policy.
     */
    suspend fun tryUpdateRecentPlantsForGrowZoneCache(growZoneNumber: GrowZone) {
        if (shouldUpdatePlantsCache()) fetchPlantsForGrowZone(growZoneNumber)
    }

    /**
     * Fetch a new list of plants from the network, and append them to [plantDao]
     */
    private suspend fun fetchRecentPlants() {
        val plants = plantService.allPlants()
        plantDao.insertAll(plants)
    }

    /**
     * Fetch a list of plants for a grow zone from the network, and append them to [plantDao]
     */
    private suspend fun fetchPlantsForGrowZone(growZone: GrowZone) {
        val plants = plantService.plantsByGrowZone(growZone)
        plantDao.insertAll(plants)
    }

    @AnyThread
    suspend fun List<Plant>.applyMainSafeSort(customSortOrder: List<String>) =
        withContext(defaultDispatcher) {
            this@applyMainSafeSort.applySort(customSortOrder)
        }

    private fun List<Plant>.applySort(customSortOrder: List<String>) : List<Plant> {
        return sortedBy { plant ->
            val positionForItem = customSortOrder.indexOf(plant.plantId).let { order ->
                if(order > -1) order else Int.MAX_VALUE
            }
            ComparablePair(positionForItem, plant.name)
        }
    }

    companion object {

        // For Singleton instantiation
        @Volatile private var instance: PlantRepository? = null

        fun getInstance(plantDao: PlantDao, plantService: NetworkService) =
            instance ?: synchronized(this) {
                instance ?: PlantRepository(plantDao, plantService).also { instance = it }
            }
    }
}
