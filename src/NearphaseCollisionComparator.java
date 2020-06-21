import java.util.Comparator;

/**
 * defines a comparator that will sort entities so that intersecting entities are last
 */
public class NearphaseCollisionComparator implements Comparator<GameEntity>
{
    public int compare(GameEntity entityA, GameEntity entityB)
    {
        BroadphaseEntity bA = entityA.m_broadphaseProxy;
        BroadphaseEntity bB = entityB.m_broadphaseProxy;
        int inNearphaseA = bA.m_nearphaseCollision;
        int inNearphaseB = bB.m_nearphaseCollision;
        if (inNearphaseB == 1 && inNearphaseA == 0)
        {
            return -1;
        }
        else if (inNearphaseA == 1 && inNearphaseB == 0)
        {
            return 1;
        }
        return 0;
    }
}
