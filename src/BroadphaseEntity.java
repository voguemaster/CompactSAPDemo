import java.util.Arrays;

/**
 * represents a game entity within the SAP broadphase
 */
public class BroadphaseEntity
{
    /// this is an upper bound for the number of entities we can overlap with at any given time
    public static final int MAX_OVERLAPS_PER_ENTITY = 10;

    
    /// coordinates of the entity's AABB top-left corner (for 3D this would be an array) in logical coordinate space.
    public int m_x, m_y;

    /// width and height in logical coordinates, together with the position this defines the entity's AABB
    public int m_width, m_height;

    /// broadphase ID - uniquly identifies the entity within the broadphase
    public int m_broadphaseID;

    /// collision filter group and mask
    public short m_filterGroup;
    public short m_filterMask;

    /// entity's minimum endpoints for axes X and Y respectively (array of 2 elements), indices into the endpoints array in the sap
    public int m_minEndpoints[];

    /// entity's maximum endpoints for axes X and Y (same concept as above), same as above
    public int m_maxEndpoints[];

    /// an array to hold the IDs of overlapping pairs within the pair manager for any entities we overlap with.
    public int m_overlappingPairs[];

    /// a flag that gets updated each frame telling us if the entity is really intersecing anyone. we use an int and set it to 0 or 1
    /// based on the intersection state. this way we can sum them up really fast (without conditionals)
    public int m_nearphaseCollision;


    /**
     * default ctor
     */
    public BroadphaseEntity()
    {
        reset();
    }


    /**
     * reset all fields but do not re-allocate stuff
     */
    public void reset()
    {
        // initialize to invalid values where appropriate (do not allocate pre-existing allocations, helps when pooling)
        m_broadphaseID = -1;
        m_x = m_y = 0;
        m_width = m_height = 0;
        m_filterGroup = m_filterMask = 0;
        if (m_minEndpoints == null)
            m_minEndpoints = new int[2];
        else
            m_minEndpoints[0] = m_minEndpoints[1] = 0;
        if (m_maxEndpoints == null)
            m_maxEndpoints = new int[2];
        else
            m_maxEndpoints[0] = m_maxEndpoints[1] = 0;

        // reset overlaps
        if (m_overlappingPairs == null)
            m_overlappingPairs = new int[MAX_OVERLAPS_PER_ENTITY];
        else
        {
            Arrays.fill(m_overlappingPairs, 0);
        }
        m_nearphaseCollision = 0;
    }


    /**
     * tells us if we're in a collision
     * @return true if we found a pair index into the pair manager array
     */
    public boolean inBroadphaseCollision()
    {
        // todo - change to a flag that we can cache..
        for(int i=0 ; i < MAX_OVERLAPS_PER_ENTITY ; i++)
        {
            if (m_overlappingPairs[i] >= 0)
                return true;    // early exit, we are colliding
        }
        return false;
    }
    
}