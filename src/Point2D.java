/**
 * A 2D point used in integer math (fixed point)
 */
public class Point2D
{
    //////////////////////////////////////////////////////////
    // members
    public long m_x, m_y;

    /**
     * Default ctor
     */
    public Point2D()
    {
        m_x = m_y = 0;
    }


    /**
     * ctor from coord values
     * @param x
     * @param y
     */
    public Point2D(long x, long y)
    {
        m_x = x;
        m_y = y;
    }


    /**
     * copy from another point
     * @param rhs
     */
    public void copy(Point2D rhs)
    {
        m_x = rhs.m_x;
        m_y = rhs.m_y;
    }


    /**
     * Vector addition
     * @param rhs
     */
    public void add(Point2D rhs)
    {
        m_x += rhs.m_x;
        m_y += rhs.m_y;
    }


    /**
     * Vector substraction
     * @param rhs
     */
    public void substract(Point2D rhs)
    {
        m_x -= rhs.m_x;
        m_y -= rhs.m_y;
    }


    /**
     * Scalar multiplication
     * @param scalar
     */
    public void multiply(long scalar)
    {
        m_x *= scalar;
        m_y *= scalar;
    }


    /**
     * Negate the vector
     */
    public void negate()
    {
        m_x = -m_x;
        m_y = -m_y;
    }


    /**
     * Returns the squared length of this point if it were a vector
     * @return The length squared
     */
    public long getLengthSquared()
    {
        return m_x*m_x + m_y*m_y;
    }


    /**
     * Returns the length of this point as a vector. The square-root is taken
     * using 16-bit fixed point. Its not advisable to use this to normalize a Point2D
     * object since it will zero out x and y. Scale the Point2D first and use it in computation
     * @return The length of this vector
     */
    public long getLength()
    {
        return MathHelper.sqrt32(m_x*m_x + m_y*m_y);
    }

}