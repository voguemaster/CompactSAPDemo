import java.awt.image.BufferedImage;

/**
 * Represents our simple entities
 */
public class GameEntity
{
    ///////////////////////////////////////////
    // graphics and rendering

    /// the entity's image for rendering (normal and when in collision - this is a simplification really)
    public BufferedImage m_image;
    public BufferedImage m_collisionImage;

    /// frame width and height
    public int m_fwidth;
    public int m_fheight;

    /// defining radius of the disc. This replaces any mesh or polytope shape in a normal engine, used among other
    /// things to test for nearphase collisions. In logical coordinates of course.
    public int m_radius;

    ///////////////////////////////////////////
    // motion related members

    /// velocity (in logical subpixels per frame, just coz its easy :)
    public int m_velocity;

    /// direction vector as a simple 2D struct
    public Point2D m_dirVector;
    public int m_dirNorm;  // direction vector length squared

    ///////////////////////////////////////////
    // collision detection members

    /// the broadphase proxy
    BroadphaseEntity m_broadphaseProxy;



    /**
     * default ctor
     */
    public GameEntity()
    {
        init();
    }


    /**
     * init method
     */
    public void init()
    {
        // init broadphase proxy if none exists
        m_broadphaseProxy = new BroadphaseEntity();
        m_dirVector = new Point2D();
    }


    /**
     * determine if we're in a collision with anyone
     * @return true if we have indication of us being in an overlapping pair
     */
    public boolean inBroadphaseCollision()
    {
        BroadphaseEntity proxy = m_broadphaseProxy;
        for(int i=0 ; i < BroadphaseEntity.MAX_OVERLAPS_PER_ENTITY ; i++)
        {
            if (proxy.m_overlappingPairs[i] >= 0)
                return true;    // early exit, we are colliding
        }
        return false;
    }
}