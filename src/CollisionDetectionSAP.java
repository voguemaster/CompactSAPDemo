/**
 * This class implements a simple collision detection system using Sweep and Prune. It is greatly simplified
 * for the task at hand and supports persistancy but direct results of all pairs all the time.
 * In addition, the endpoint structure is really an int and nothing else since its quite enough for a simple
 * action game for J2ME
 * The endpoint's break down is: 1 bit (min/max flag), 15 bits entity ID in the SAP (assumed unsigned),
 * and 16 bits is the actual endpoint value
 */
public class CollisionDetectionSAP
{
    ///////////////////////////////////////////////////////////////
    // constants

    // maximum flag bitmask and entity ID bitmask
    public static final long MAX_FLAG_BITMASK = (long)1 << 63;  // = 0x8000000000000000
    public static final int ENTITY_ID_BITMASK = 0x7FFFFFFF;
    public static final long POSITION_BITMASK = 0xFFFFFFFFL;     // = 0xFFFFFFFF (4GB = 32bit). if we use int here we'll have -1 which gets signed extended

    // maximum allowed entities in the SAP
    public static final int MAX_ENTITIES = 10000;

    // maximum supported overlaps
    public static final int MAX_OVERLAPS = 100000;

    // invalid pair ID
    public static final int INVALID_PAIR_ID = -1;

    ///////////////////////////////////////////////////////////////
    // members

    // an array of entities inside the SAP (usually this will be an array of boxes but this is ok)
    private BroadphaseEntity[] m_entities;
    private int m_numEntities;

    // endpoint arrays for the two axes
    private long[] m_xEndPoints;
    private long[] m_yEndPoints;

    // the pair manager is a simple array
    private int[] m_pairManager;
    private int m_pairsCount;

    // storage for entities that have overlap with an entity moving in the array
    private BroadphaseEntity[] m_removedPairsEntities;


    /**
     * Default ctor
     */
    public CollisionDetectionSAP()
    {
        // allocate array to hold entities within the sap and a sentinel
        m_entities = new BroadphaseEntity[MAX_ENTITIES+1];
        m_numEntities = 0;

        // allocate pair manager array that supports an initial possible overlaps of 256
        m_pairManager = new int[MAX_OVERLAPS];
        m_pairsCount = 0;

        // allocate storage for removal purposes
        m_removedPairsEntities = new BroadphaseEntity[BroadphaseEntity.MAX_OVERLAPS_PER_ENTITY];

        // allocate endpoint arrays for x and y
        m_xEndPoints = new long[(MAX_ENTITIES+1)*2];
        m_yEndPoints = new long[(MAX_ENTITIES+1)*2];

        // allocate sentinel entity (does not count against the maximum)
        m_entities[0] = new BroadphaseEntity();
        m_entities[0].m_broadphaseID = 0;

        // set sentinel endpoints within the entity
        for(int i=0 ; i < 2 ; i++)
        {
            m_entities[0].m_minEndpoints[i] = 0;
            m_entities[0].m_maxEndpoints[i] = 1;
        }

        // set sentinel within the arrays (has entity ID = 0)
        m_xEndPoints[0] = encodeEndpoint(false, 0, Integer.MIN_VALUE);
        m_xEndPoints[1] = encodeEndpoint(true, 0, Integer.MAX_VALUE);  // this endpoint is max and its value is maximal
        m_yEndPoints[0] = encodeEndpoint(false, 0, Integer.MIN_VALUE);
        m_yEndPoints[1] = encodeEndpoint(true, 0, Integer.MAX_VALUE);
    }


    ///////////////////////////////////////////////////////////////
    // broadphase interface to the outside world (the logic)

    /**
     * Adds an entity to the collision detection system
     * @param entity
     * @param bCanUpdateOverlaps if true the entity will not update overlaps at all
     */
    public void addEntity(BroadphaseEntity entity, boolean bCanUpdateOverlaps)
    {
        // if the entity cannot collide with anything, no need to bother us
        if (entity.m_filterGroup == 0 || entity.m_filterMask == 0 || entity.m_broadphaseID >= 0)
        {
            return;
        }

        // initialize collision info in the entity
        for(int i=0 ; i < BroadphaseEntity.MAX_OVERLAPS_PER_ENTITY ; i++)
        {
            entity.m_overlappingPairs[i] = INVALID_PAIR_ID;
        }

        // add the entity to our list (id 0 is reserved for the sentinel)
        entity.m_broadphaseID = m_numEntities+1;
        m_entities[m_numEntities+1] = entity;
        m_numEntities++;

        // the sentinel defines the new end of the endpoint array, update its max endpoint indices
        for(int i=0 ; i < 2 ; i++)
        {
            m_entities[0].m_maxEndpoints[i] += 2;
        }

        // current number of edges without counting the sentinel
        int nedges = m_numEntities*2;

        // move the max value of the sentinel to its new position
        m_xEndPoints[nedges+1] = m_xEndPoints[nedges-1];
        m_yEndPoints[nedges+1] = m_yEndPoints[nedges-1];

        // insert minimum endpoint "structs" for the new entity
        int minx = entity.m_x;
        int miny = entity.m_y;
        m_xEndPoints[nedges-1] = encodeEndpoint(false, entity.m_broadphaseID, minx);
        m_yEndPoints[nedges-1] = encodeEndpoint(false, entity.m_broadphaseID, miny);

        // insert maximum endpoint "structs" for the new entity
        m_xEndPoints[nedges] = encodeEndpoint(true, entity.m_broadphaseID, minx+entity.m_width);
        m_yEndPoints[nedges] = encodeEndpoint(true, entity.m_broadphaseID, miny+entity.m_height);

        // set the new endpoint indices in the entity
        for(int i=0 ; i < 2 ; i++)
        {
            entity.m_minEndpoints[i] = nedges-1;
            entity.m_maxEndpoints[i] = nedges;
        }

        // sort both min and max endpoints on each axis to their proper position. only update overlaps at the second axis
        sortMinDown(0, m_xEndPoints, entity.m_minEndpoints[0], false);
        sortMaxDown(0, m_xEndPoints, entity.m_maxEndpoints[0], false);
        sortMinDown(1, m_yEndPoints, entity.m_minEndpoints[1], bCanUpdateOverlaps);
        sortMaxDown(1, m_yEndPoints, entity.m_maxEndpoints[1], bCanUpdateOverlaps);
    }


    /**
     * Removes an entity from the SAP structures by removing it from the pair manager
     * and shuffling its endpoints to infinity (without adding/removing pairs)
     * @param entity
     */
    public void removeEntity(BroadphaseEntity entity)
    {
        if (entity.m_broadphaseID < 0)
        {
            return;
        }

        // remove the entity from the pair manager
        removePairsContainingEntity(entity);

        // current number of edges without counting the sentinel
        int nedges = m_numEntities*2;

        // move the sentinel's max endpoints down to reflect the new array size
        for(int i=0 ; i < 2 ; i++)
        {
            m_entities[0].m_maxEndpoints[i] -= 2;
        }

        // shuffle the max endpoints of the entity to the end of the array by changing their pos to MAX_VALUE
        int maxPos = entity.m_maxEndpoints[0];
        m_xEndPoints[maxPos] = encodeEndpoint(true, entity.m_broadphaseID, Integer.MAX_VALUE);
        sortMaxUp(0, m_xEndPoints, maxPos, false);

        maxPos = entity.m_maxEndpoints[1];
        m_yEndPoints[maxPos] = encodeEndpoint(true, entity.m_broadphaseID, Integer.MAX_VALUE);
        sortMaxUp(1, m_yEndPoints, maxPos, false);

        // shuffle the min endpoints of the entity to the end of the array by changing their pos to MAX_VALUE
        int minPos = entity.m_minEndpoints[0];
        m_xEndPoints[minPos] = encodeEndpoint(false, entity.m_broadphaseID, Integer.MAX_VALUE);
        sortMinUp(0, m_xEndPoints, minPos, false);

        minPos = entity.m_minEndpoints[1];
        m_yEndPoints[minPos] = encodeEndpoint(false, entity.m_broadphaseID, Integer.MAX_VALUE);
        sortMinUp(1, m_yEndPoints, minPos, false);

        // move the max endpoints of the sentinel to their new position
        m_xEndPoints[nedges-1] = encodeEndpoint(true, 0, Integer.MAX_VALUE);
        m_yEndPoints[nedges-1] = encodeEndpoint(true, 0, Integer.MAX_VALUE);

        // clear unnecessary entries
        m_xEndPoints[nedges] = m_xEndPoints[nedges+1] = 0;
        m_yEndPoints[nedges] = m_yEndPoints[nedges+1] = 0;

        // remove entity from our list by replacing it with the last entity and clearing last position
        // before we do we must cache overlapping entities for the (last) entity because its index is going
        // to change and so we must update all overlapping entities
        // if the last entity is actually the entity being removed, do nothing as it will ruin the endpoints of the sentinel
        if (entity.m_broadphaseID < m_numEntities)
        {
            BroadphaseEntity lastEntity = m_entities[m_numEntities];  // since 0 is reserved for the sentinel
            cacheOverlapsForEntity(lastEntity);
            removePairsContainingEntity(lastEntity);

            // swap the entity to be removed with the last entity (last entity's overlaps become invalid)
            int id = entity.m_broadphaseID;
            m_entities[id] = lastEntity;
            lastEntity.m_broadphaseID = id;  // update the ID of the entity that moved

            // entity has changed broadphase ID, restore overlaps with previously cached entities
            restoreOverlapsFromCache(lastEntity);

            // update the endpoint "structs" with the ID of the moved entity because its position within the list has changed
            long minEP = m_xEndPoints[lastEntity.m_minEndpoints[0]]; // min in X
            m_xEndPoints[lastEntity.m_minEndpoints[0]] = encodeEndpoint(false, lastEntity.m_broadphaseID, getPositionFromEndpoint(minEP));
            long maxEP = m_xEndPoints[lastEntity.m_maxEndpoints[0]]; // max in X
            m_xEndPoints[lastEntity.m_maxEndpoints[0]] = encodeEndpoint(true, lastEntity.m_broadphaseID, getPositionFromEndpoint(maxEP));
            minEP = m_yEndPoints[lastEntity.m_minEndpoints[1]]; // min in Y
            m_yEndPoints[lastEntity.m_minEndpoints[1]] = encodeEndpoint(false, lastEntity.m_broadphaseID, getPositionFromEndpoint(minEP));
            maxEP = m_yEndPoints[lastEntity.m_maxEndpoints[1]]; // max in Y
            m_yEndPoints[lastEntity.m_maxEndpoints[1]] = encodeEndpoint(true, lastEntity.m_broadphaseID, getPositionFromEndpoint(maxEP));
        }

        // clear last pos and update count
        m_entities[m_numEntities] = null;
        m_numEntities--;

        // reset ID of entity removed
        entity.m_broadphaseID = -1;
    }


    /**
     * clears all entities from the collision detection but keep the sentinel
     */
    public void clearEntities()
    {
        // in order to clear all entities with as minimum overhead, remove them from the last
        // to the first
        while(m_numEntities > 0)
        {
            BroadphaseEntity entity = m_entities[m_numEntities];
            removeEntity(entity);
        }
    }


    /**
     * Given an entity, cache entities it overlaps in the storage array
     * @param entity
     */
    private void cacheOverlapsForEntity(BroadphaseEntity entity)
    {
        for(int i=0 ; i < BroadphaseEntity.MAX_OVERLAPS_PER_ENTITY ; i++)
        {
            int pairID = entity.m_overlappingPairs[i];
            if (pairID >= 0)
            {
                // get pair and entities (one of the is our entity)
                int pair = m_pairManager[pairID];
                BroadphaseEntity eA = getFirstEntityFromPair(pair);
                BroadphaseEntity eB = getSecondEntityFromPair(pair);

                // set the other entity (not the one that is moving) in cache
                m_removedPairsEntities[i] = (eA == entity) ? eB : eA;
            }
            else
            {
                m_removedPairsEntities[i] = null;   // no overlap
            }
        }
    }


    /**
     * Given an entity and other entities cached in the storage array, restore overlaps
     * @param entity
     */
    private void restoreOverlapsFromCache(BroadphaseEntity entity)
    {
        for(int i=0 ; i < BroadphaseEntity.MAX_OVERLAPS_PER_ENTITY ; i++)
        {
            BroadphaseEntity other = m_removedPairsEntities[i];
            if (other != null)
                addOverlappingPair(entity, other);
        }
    }


    /**
     * Update an entity's bounds within the SAP based on its new position/orientation
     * @param entity
     */
    public void updateEntityBounds(BroadphaseEntity entity)
    {
        if (entity.m_broadphaseID < 0)
        {
            // todo remove this when done
            System.out.println("CollisionDetectionSAP.updateEntityBounds: invalid entity ID");
            return;
        }

        // obtain new min and max for X axis
        int min = entity.m_x;
        int max = min + entity.m_width;

        // obtain current min and max endpoints for X
        int minEPIndex = entity.m_minEndpoints[0];
        int maxEPIndex = entity.m_maxEndpoints[0];

        // determine direction to sort based on difference in position
        int dmin = min - getPositionFromEndpoint(m_xEndPoints[minEPIndex]);
        int dmax = max - getPositionFromEndpoint(m_xEndPoints[maxEPIndex]);

        // update endpoint struct
        m_xEndPoints[minEPIndex] = encodeEndpoint(false, entity.m_broadphaseID, min);
        m_xEndPoints[maxEPIndex] = encodeEndpoint(true, entity.m_broadphaseID, max);

        // expand bounds
        if (dmin < 0)
            sortMinDown(0, m_xEndPoints, minEPIndex, true);
        if (dmax > 0)
            sortMaxUp(0, m_xEndPoints, maxEPIndex, true);

        // shrink bounds
        if (dmin > 0)
            sortMinUp(0, m_xEndPoints, minEPIndex, true);
        if (dmax < 0)
            sortMaxDown(0, m_xEndPoints, maxEPIndex, true);

        // obtain new min and max for Y axis
        min = entity.m_y;
        max = min + entity.m_height;

        // obtain current min and max endpoints
        minEPIndex = entity.m_minEndpoints[1];
        maxEPIndex = entity.m_maxEndpoints[1];

        // determine direction of sort based on difference in position
        dmin = min - getPositionFromEndpoint(m_yEndPoints[minEPIndex]);
        dmax = max - getPositionFromEndpoint(m_yEndPoints[maxEPIndex]);

        // update endpoint struct
        m_yEndPoints[minEPIndex] = encodeEndpoint(false, entity.m_broadphaseID, min);
        m_yEndPoints[maxEPIndex] = encodeEndpoint(true, entity.m_broadphaseID, max);

        // expand bounds
        if (dmin < 0)
            sortMinDown(1, m_yEndPoints, minEPIndex, true);
        if (dmax > 0)
            sortMaxUp(1, m_yEndPoints, maxEPIndex, true);

        // shrink bounds
        if (dmin > 0)
            sortMinUp(1, m_yEndPoints, minEPIndex, true);
        if (dmax < 0)
            sortMaxDown(1, m_yEndPoints, maxEPIndex, true);
    }


    /**
     * Encode an endpoint based on the data provided
     * @param isMax
     * @param broadphaseID
     * @param position
     * @return Endpoint "struct"
     */
    private long encodeEndpoint(boolean isMax, int broadphaseID, int position)
    {
        if (isMax)
        {
            return (MAX_FLAG_BITMASK | ((long)broadphaseID << 32)) | ((long)position & POSITION_BITMASK);
        }
        else
        {
            return ((long)broadphaseID << 32) | ((long)position & POSITION_BITMASK);
        }
    }


    /**
     * Obtain the overlapping pairs of objects. Used by us to actually perform finer collision detection
     * for those pairs of entities that need it.
     */
    public int[] getOverlappingPairs()
    {
        return m_pairManager;
    }


    /**
     * Returns the number of overlaps currently known to the SAP
     * @return return num of pairs
     */
    public int getPairsCount()
    {
        return m_pairsCount;
    }


    /**
     * Tests whether the endpoint is a maximum or minimum endpoint
     * @param endpoint
     * @return true if the endpoint is a max endpoint
     */
    private boolean isMax(long endpoint)
    {
        return ((endpoint & MAX_FLAG_BITMASK) == MAX_FLAG_BITMASK);
    }


    /**
     * Obtain the entity from the endpoint
     * @param endpoint
     * @return get the entity from the endpoint "struct"
     */
    private BroadphaseEntity getEntityFromEndpoint(long endpoint)
    {
        int id = (int)(endpoint >> 32) & ENTITY_ID_BITMASK;
        return m_entities[id];
    }


    /**
     * Obtain the position of the endpoint
     * @param endpoint
     * @return get the position encoded in the endpoint "struct"
     */
    private int getPositionFromEndpoint(long endpoint)
    {
        return (int)endpoint;
    }



    /**
     * Shuffles a minimum endpoint of an entity down the endpoint array to its proper position
     * a minimum endpoint moving down can only add overlaps (when it passes a max endpoint of another entity)
     * @param axis The axis (0 = x, 1 = y) to sort on
     * @param endpoints The endpoints array to operate on (x or y)
     * @param endpointPos The position of the endpoint
     * @param updateOverlaps True will update overlaps during the sorting
     */
    private void sortMinDown(int axis, long[] endpoints, int endpointPos, boolean updateOverlaps)
    {
        long minEP = endpoints[endpointPos];
        long prevEP = endpoints[endpointPos-1];
        BroadphaseEntity entity = getEntityFromEndpoint(minEP);

        // move down the array until we are sorted
        while(getPositionFromEndpoint(minEP) < getPositionFromEndpoint(prevEP))
        {
            // get entity of the endpoint before us
            BroadphaseEntity prevEntity = getEntityFromEndpoint(prevEP);

            // if the endpoint before us is a max endpoint, test for overlap
            if (isMax(prevEP))
            {
                // test overlap if needed
                if (updateOverlaps && testOverlap(axis, entity, prevEntity))
                {
                    // add overlap for the two entities
                    addOverlappingPair(entity, prevEntity);
                }

                // the max endpoint of the entity before us moves up
                prevEntity.m_maxEndpoints[axis]++;
            }
            else
            {
                // the min endpoint of the entity before us moves up
                prevEntity.m_minEndpoints[axis]++;
            }

            // the moving min endpoint moves down
            entity.m_minEndpoints[axis]--;

            // swap the actual endpoints
            endpoints[endpointPos] = prevEP;
            endpoints[endpointPos-1] = minEP;

            // move down the array
            endpointPos--;
            prevEP = endpoints[endpointPos-1];
        }
    }


    /**
     * Shuffles a minimum endpoint of an entity up the endpoint array to its proper position
     * Moving up it can only remove overlaps (when it passes a max endpoint of another entity)
     * @param axis The axis (0 = x, 1 = y) to sort on
     * @param endpoints The endpoint array
     * @param endpointPos The position of the endpoint
     * @param updateOverlaps True will update overlaps during sorting
     */
    private void sortMinUp(int axis, long[] endpoints, int endpointPos, boolean updateOverlaps)
    {
        long minEP = endpoints[endpointPos];
        long nextEP = endpoints[endpointPos+1];
        BroadphaseEntity entity = getEntityFromEndpoint(minEP);

        // move up the array until we are sorted
        while(getPositionFromEndpoint(minEP) > getPositionFromEndpoint(nextEP))
        {
            // get entity after us
            BroadphaseEntity nextEntity = getEntityFromEndpoint(nextEP);

            // if the endpoint after our endpoint is max, remove overlap
            if (isMax(nextEP))
            {
                if (updateOverlaps)
                {
                    // remove overlap between the two entities
                    removeOverlappingPair(entity, nextEntity);
                }

                // the max endpoint of the entity after us moves down
                nextEntity.m_maxEndpoints[axis]--;
            }
            else
            {
                // the min endpoint of the entity after us moves down
                nextEntity.m_minEndpoints[axis]--;
            }

            // the moving min endpoint moves up
            entity.m_minEndpoints[axis]++;

            // swap the actual endpoints
            endpoints[endpointPos] = nextEP;
            endpoints[endpointPos+1] = minEP;

            // move forward in the array
            endpointPos++;
            nextEP = endpoints[endpointPos+1];
        }
    }


    /**
     * Shuffles a max endpoint down the endpoint array to its proper position
     * Moving down it can only remove overlaps (when it passes a min endpoint of another entity)
     * @param axis The axis (0 = x, 1 = y) to sort on
     * @param endpoints
     * @param endpointPos
     * @param updateOverlaps
     */
    private void sortMaxDown(int axis, long[] endpoints, int endpointPos, boolean updateOverlaps)
    {
        long maxEP = endpoints[endpointPos];
        long prevEP = endpoints[endpointPos-1];
        BroadphaseEntity entity = getEntityFromEndpoint(maxEP);

        // move down the array until we are sorted
        while(getPositionFromEndpoint(maxEP) < getPositionFromEndpoint(prevEP))
        {
            // get entity before us
            BroadphaseEntity prevEntity = getEntityFromEndpoint(prevEP);

            // if the endpoint before us is a minimum, remove overlap
            if (!isMax(prevEP))
            {
                if (updateOverlaps)
                {
                    // remove overlap between the two entities
                    removeOverlappingPair(entity, prevEntity);
                }

                // the min endpoint of the entity before us moves up
                prevEntity.m_minEndpoints[axis]++;
            }
            else
            {
                // the max endpoint of the entity before us moves us
                prevEntity.m_maxEndpoints[axis]++;
            }

            // the moving max endpoint moves down
            entity.m_maxEndpoints[axis]--;

            // swap the actual endpoints
            endpoints[endpointPos] = prevEP;
            endpoints[endpointPos-1] = maxEP;

            // move down the array
            endpointPos--;
            prevEP = endpoints[endpointPos-1];
        }
    }


    /**
     * Shuffles a max endpoint up the endpoint array to its proper position
     * Moving up it can only add overlaps (when it passes a min endpoint of another entity)
     * @param axis The axis (0 = x, 1 = y) to sort on
     * @param endpoints
     * @param endpointPos
     * @param updateOverlaps
     */
    private void sortMaxUp(int axis, long[] endpoints, int endpointPos, boolean updateOverlaps)
    {
        long maxEP = endpoints[endpointPos];
        long nextEP = endpoints[endpointPos+1];
        BroadphaseEntity entity = getEntityFromEndpoint(maxEP);

        // move up the array until we are sorted
        while(getPositionFromEndpoint(maxEP) > getPositionFromEndpoint(nextEP))
        {
            // get entity after us
            BroadphaseEntity nextEntity = getEntityFromEndpoint(nextEP);

            // if the endpoint after us is a minimum, add overlap if needed
            if (!isMax(nextEP))
            {
                // test overlap if needed
                if (updateOverlaps && testOverlap(axis, entity, nextEntity))
                {
                    // add overlap for the two entities
                    addOverlappingPair(entity, nextEntity);
                }

                // the min endpoint of the entity after us moves down
                nextEntity.m_minEndpoints[axis]--;
            }
            else
            {
                // the max endpoint of the entity after us moves down
                nextEntity.m_maxEndpoints[axis]--;
            }

            // the moving max endpoint moves up
            entity.m_maxEndpoints[axis]++;

            // swap the actual endpoints
            endpoints[endpointPos] = nextEP;
            endpoints[endpointPos+1] = maxEP;

            // move up in array
            endpointPos++;
            nextEP = endpoints[endpointPos+1];
        }
    }


    /**
     * Used by the sorting update code to test overlap on the axis not currently
     * being updated
     * @param ignoreAxis
     * @param entityA
     * @param entityB
     * @return True if there's an overlap on the axis not being ignored
     */
    private boolean testOverlap(int ignoreAxis, BroadphaseEntity entityA, BroadphaseEntity entityB)
    {
        for(int i=0 ; i < 2 ; i++)
        {
            if (i != ignoreAxis)
            {
                // obtain endpoints array
                long[] endpoints = (i == 0) ? m_xEndPoints : m_yEndPoints;
                int minA = getPositionFromEndpoint(endpoints[entityA.m_minEndpoints[i]]);
                int maxA = getPositionFromEndpoint(endpoints[entityA.m_maxEndpoints[i]]);
                int minB = getPositionFromEndpoint(endpoints[entityB.m_minEndpoints[i]]);
                int maxB = getPositionFromEndpoint(endpoints[entityB.m_maxEndpoints[i]]);

                // perform test on this axis
                if (maxA < minB || maxB < minA)
                {
                    // no overlap, early exit
                    return false;
                }
            }
        }
        return true;
    }


    /**
     * Used to perform a singular test between two entities, assuming they are
     * in the view (hence registered in the SAP)
     * @param entityA
     * @param entityB
     * @return True if their AABBs overlap
     */
    public boolean testEntitiesOverlap(BroadphaseEntity entityA, BroadphaseEntity entityB)
    {
        return testOverlap(-1, entityA, entityB);
    }


    /**
     * Adds an overlap between the two entities. The pair is encoded into an int
     * with both its 16 bit parts being the broadphase ID of the entities. The encoded int
     * is set in a reduced matrix based on the smaller entity's ID, supporting up to 5 overlaps
     * per entity
     * @param entityA
     * @param entityB
     */
    private void addOverlappingPair(BroadphaseEntity entityA, BroadphaseEntity entityB)
    {
        // before we add the pair to the pair manager, make sure entities can collide
        // with regards to the collision rules
        if (!needsCollision(entityA, entityB))
            return;

        // find the pair in the pair manager and return it if exists
        int pairID = FindPair(entityA, entityB);
        if (pairID >= 0)
            return;

        // todo remove when done
        //System.out.println("CollisionDetectionSAP.addOverlappingPair: adding pair for entities with IDs "+
        //        entityA.m_broadphaseID+", "+entityB.m_broadphaseID);

        // add the pair to the pair manager
        m_pairManager[m_pairsCount] = createPair(entityA, entityB);

        // add the overlapping pair to both the entities
        for(int i=0 ; i < BroadphaseEntity.MAX_OVERLAPS_PER_ENTITY ; i++)
        {
            if (entityA.m_overlappingPairs[i] == INVALID_PAIR_ID || entityA.m_overlappingPairs[i] == m_pairsCount)
            {
                // set in entity
                entityA.m_overlappingPairs[i] = m_pairsCount;
                break;
            }
        }
        for(int i=0 ; i < BroadphaseEntity.MAX_OVERLAPS_PER_ENTITY ; i++)
        {
            if (entityB.m_overlappingPairs[i] == INVALID_PAIR_ID || entityB.m_overlappingPairs[i] == m_pairsCount)
            {
                // set in entity
                entityB.m_overlappingPairs[i] = m_pairsCount;
                break;
            }
        }

        // update count
        m_pairsCount++;
    }


    /**
     * Removes an overlap between the two entities if one is found
     * @param entityA
     * @param entityB
     * @return The pair that was removed
     */
    private int removeOverlappingPair(BroadphaseEntity entityA, BroadphaseEntity entityB)
    {
        // create a pair ID
        int pair = createPair(entityA, entityB);

        // find the pair in the pair manager and if doesn't exist, do nothing
        int pairID = FindPair(entityA, entityB);
        if (pairID < 0)
            return pair;

        // todo remove when done
        //System.out.println("CollisionDetectionSAP.removeOverlappingPair: removing pair for entities with IDs "+
        //        entityA.m_broadphaseID+", "+entityB.m_broadphaseID);

        // clear the pair ID from both the entities
        for(int i=0 ; i < BroadphaseEntity.MAX_OVERLAPS_PER_ENTITY ; i++)
        {
            if (entityA.m_overlappingPairs[i] == pairID)
            {
                entityA.m_overlappingPairs[i] = INVALID_PAIR_ID;
            }
            if (entityB.m_overlappingPairs[i] == pairID)
            {
                entityB.m_overlappingPairs[i] = INVALID_PAIR_ID;
            }
        }

        // move the last pair in the manager to the removed pair location
        m_pairManager[pairID] = m_pairManager[m_pairsCount-1];

        // since the last pair has changed location, update the entities that contain that ID with the new location
        entityA = getFirstEntityFromPair(m_pairManager[pairID]);
        entityB = getSecondEntityFromPair(m_pairManager[pairID]);
        for(int i=0 ; i < BroadphaseEntity.MAX_OVERLAPS_PER_ENTITY ; i++)
        {
            if (entityA.m_overlappingPairs[i] == m_pairsCount-1)
            {
                entityA.m_overlappingPairs[i] = pairID;
            }
            if (entityB.m_overlappingPairs[i] == m_pairsCount-1)
            {
                entityB.m_overlappingPairs[i] = pairID;
            }
        }

        // clear the last pair from the manager and update count
        m_pairManager[m_pairsCount-1] = 0;
        m_pairsCount--;

        // return the pair
        return pair;
    }


    /**
     * Find the pair for the two entities in the pair manager if they are overlapping
     * if not, return -1
     * @param entityA
     * @param entityB
     * @return -1 if not found, the index of the pair in the pair manager otherwise
     */
    private int FindPair(BroadphaseEntity entityA, BroadphaseEntity entityB)
    {
        int pair = createPair(entityA, entityB);

        // iterate overlaps stored within the first entity
        for(int i=0 ; i < BroadphaseEntity.MAX_OVERLAPS_PER_ENTITY ; i++)
        {
            // obtain pair
            int pairID = entityA.m_overlappingPairs[i];
            if (pairID > INVALID_PAIR_ID && m_pairManager[pairID] == pair)
            {
                // found them to be overlapping, return this pair ID
                return pairID;
            }
        }

        // not found to be overlapping, return invalid pair ID
        return -1;
    }


    /**
     * Removes any overlapping pairs containing the entity
     * @param entity
     */
    private void removePairsContainingEntity(BroadphaseEntity entity)
    {
        // iterate pairs for this entity and remove as necessary
        for(int i=0 ; i < BroadphaseEntity.MAX_OVERLAPS_PER_ENTITY ; i++)
        {
            int pairID = entity.m_overlappingPairs[i];
            if (pairID >= 0)
            {
                int pair = m_pairManager[pairID];
                removeOverlappingPair(getFirstEntityFromPair(pair), getSecondEntityFromPair(pair));
            }
        }
    }


    /**
     * encodes the broadphase IDs of the entities and gives us a pair
     * @param entityA
     * @param entityB
     * @return The encoded pair to be put in the pair manager
     */
    private int createPair(BroadphaseEntity entityA, BroadphaseEntity entityB)
    {
        // sort the IDs so the smaller is in A
        int idA = entityA.m_broadphaseID;
        int idB = entityB.m_broadphaseID;
        if (idB < idA)
        {
            int tmp = idA;
            idA = idB;
            idB = tmp;
        }

        // encode the smaller ID in the lower part of the int
        return ((idB << 16) | idA);
    }


    /**
     * Obtain the first (smaller ID) entity encoded in the pair
     * @param pair
     * @return The entity if its in the SAP
     */
    public BroadphaseEntity getFirstEntityFromPair(int pair)
    {
        int id = pair & 0xFFFF;
        return m_entities[id];
    }


    /**
     * Obtain the second (higher ID) entity encoded in the pair
     * @param pair
     * @return The second entity
     */
    public BroadphaseEntity getSecondEntityFromPair(int pair)
    {
        int id = (pair >> 16) & 0xFFFF;
        return m_entities[id];
    }


    /**
     * Determines if the entities can even collide based on the rules set by the user (group and mask)
     * @param entityA
     * @param entityB
     * @return True if collision can even occur
     */
    public boolean needsCollision(BroadphaseEntity entityA, BroadphaseEntity entityB)
    {
        // check group and mask for possible collision
        boolean canCollide = (entityA.m_filterGroup & entityB.m_filterMask) != 0;
        canCollide = canCollide && ((entityB.m_filterGroup & entityA.m_filterMask) != 0);
        return canCollide;
    }

}