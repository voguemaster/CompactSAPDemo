
/**
 * Contains useful math functions, including fixed point functions for various tasks
 */
public class MathHelper
{
    /////////////////////////////////////////////////////////////////
    // constants

    // cordic fractional bits
    public static final int FRACTIONAL_BITS = 16;
    public static final int ONE = 1 << FRACTIONAL_BITS;
    public static final int EPSILON = 655;  // 0.01 * 65536

    // cordic scale
    // 291597966 = 0.2715717684432241 * 2^30, valid for j>13
    public static final int COSCALE = 0x11616E8E;

    // a quarter in degrees (in fixed point 16 fractional bits)
    public static final long QUARTER = 90 << 16;

    // max iterations to compute arctan (taken from Cordic)
    public static final int MAX_ITERATIONS = 22;

    // arctan lookup table
    public static final long arctanTable[] = {
        4157273, 2949120, 1740967, 919879, 466945, 234379, 117304, 58666,
        29335, 14668, 7334, 3667, 1833, 917, 458, 229,
        115, 57, 29, 14, 7, 4, 2, 1
    };


    /**
     * compute the sqrt of an integer using 16 bit fixed point math
     * @param value
     * @return The sqrt
     */
    public static long sqrt32(long value)
    {
        int g = 0;
        int bshft = 15;
        int b = 1 << bshft;
        do
        {
            int t = (g+g+b) << bshft;
            if (value >= t)
            {
                g += b;
                value -= t;
            }
            b >>= 1;
        } while (bshft-- > 0);

        return g;
    }


    /**
     * Count the number of shifts the number's MSB has from 1
     * @param n
     * @return The logarithm of the number (assuming only one bit is set!)
     */
    public static int countShifts(int n, int maxShifts)
    {
        int shifts = 0;
        while(maxShifts >= 0 && (n > 1))
        {
            n >>= 1;    // shift another bit down
            shifts++;
        }
        return shifts;
    }


    /**
     * given a number, divide it by 10 and return the result rounded up. If the number is larger than 150,000
     * error begins to be significant (actually, 0x25000 is ok, which is 151552 / 10 = 15156)
     * @param n
     * @return The number divided by 10
     */
    public static int fastDiv10(int n)
    {
        // multiply by 6554
        n *= 6554;
        return (n >> 16);   // divide by 65536
    }

    /**
     * given a number, divide it by 10 and return the result rounded up. If the number is larger than 630
     * error starts to build up
     * @param n
     * @return The number divided by 10
     */
    public static int fastDiv10Small(int n)
    {
        // multiply by 51, divide by 256
        n = n + (n << 1) + (n << 4) + (n << 5);
        n = n >> 8; // n is now n/5
        return (n+1) >> 1;  // divide by 2
    }


    /**
     * Fills p with x,y that corresponds with a unit vector with angle theta
     * Both x and y are 16 bit fractional fixed point
     * @param p
     * @param theta
     */
    public static void UnitVector(Point2D p, long theta)
    {
        p.m_x = COSCALE;
        p.m_y = 0;
        CordicRotate(p, theta);
    }


    /**
     * Rotate the vector stored in P by angle theta
     * @param p
     * @param theta
     */
    public static void Rotate(Point2D p, long theta)
    {
        if ((p.m_x == 0) && (p.m_y == 0) && (theta == 0))
            return;

        // prenormalize for better accuracy
        int shiftExp = CordicNormalize(p);
        CordicRotate(p, theta);

        // multiply by the cordic factor
        p.m_x = FractionMultiply(p.m_x, COSCALE);
        p.m_y = FractionMultiply(p.m_y, COSCALE);
        if (shiftExp < 0)
        {
            p.m_x >>= -shiftExp;
            p.m_y >>= -shiftExp;
        }
        else
        {
            p.m_x <<= shiftExp;
            p.m_y <<= shiftExp;
        }
    }


    /**
     * Compute arctan of the vector passed, where tan(theta) = y/x
     * @param p
     * @return The arctan corresponding to the vector, p is undefined
     */
    public static long atan2(Point2D p)
    {
        if ((p.m_x == 0) && (p.m_y == 0))
            return 0;

        // normalize for better accuracy
        CordicNormalize(p);
        CordicPolarize(p);
        return p.m_y;
    }


    /**
     * Convert vector to polar coordinates
     * @param p
     */
    public static void Polarize(Point2D p)
    {
        if ((p.m_x == 0) && (p.m_y == 0))
            return;

        int shiftExp = CordicNormalize(p);
        CordicPolarize(p);

        // multiply radius by cordic factor
        p.m_x = FractionMultiply(p.m_x, COSCALE);
        p.m_x = (shiftExp < 0) ? (p.m_x >> -shiftExp) : (p.m_x << shiftExp);
    }


    /**
     * Prenormalize the Point2D object and returns the block exponent. used by atan2 and rotate
     * @param p The point to normalize. on exit the point is normalized
     * @return The block exponent
     */
    private static int CordicNormalize(Point2D p)
    {
        long x, y;
        int signx, signy;
        int shiftExp = 0;
        signx = signy = 1;

        // init x and y to be in absolute value but remember their signs
        x = p.m_x;
        y = p.m_y;
        if (x < 0)
        {
            // make it positive but remember sign
            x = -x;
            signx = -signx;
        }
        if (y < 0)
        {
            // make is positive but remember sign
            y = -y;
            signy = -signy;
        }

        // prenormalize
        if (x < y)
        {
            // this means |y| > |x|
            while (y < (1 << 27)) {
                x <<= 1;
                y <<= 1;
                shiftExp--;
            }
            while (y > (1 << 28)) {
                x >>= 1;
                y >>= 1;
                shiftExp++;
            }
        }
        else
        {
            // this means |x| > |y|
            while (x < (1 << 27)) {
                x <<= 1;
                y <<= 1;
                shiftExp--;
            }
            while (x > (1 << 28)) {
                x >>= 1;
                y >>= 1;
                shiftExp++;
            }
        }

        // set result in point
        p.m_x = (signx < 0) ? -x : x;
        p.m_y = (signy < 0) ? -y : y;
        return shiftExp;
    }


    /**
     * Perform rotations for a normalized vector based on the Cordic algorithm.
     * @param p The input point. On exit contains the resulting new vector
     */
    private static void CordicRotate(Point2D p, long theta)
    {
        long x, y, xtemp;
        int arctanPtr;  // index into the arctan table

        // init
        x = p.m_x;
        y = p.m_y;

        // normalize the angle to be between -90 and 90
        while(theta < -QUARTER)
        {
            x = -x;
            y = -y;
            theta += 2*QUARTER;
        }
        while(theta > QUARTER)
        {
            x = -x;
            y = -y;
            theta -= 2*QUARTER;
        }

        // initial rotation done with left shift
        arctanPtr = 0;
        if (theta < 0)
        {
            xtemp = x + (y << 1);
            y = y - (x << 1);
            x = xtemp;
            theta += arctanTable[arctanPtr++];
        }
        else
        {
            xtemp = x - (y << 1);
            y = y + (x << 1);
            x = xtemp;
            theta -= arctanTable[arctanPtr++];
        }

        // next rotations are done with right shifts
        for(int i=0 ; i < MAX_ITERATIONS ; i++)
        {
            if (theta < 0)
            {
                xtemp = x + (y >> i);
                y = y - (x >> i);
                x = xtemp;
                theta += arctanTable[arctanPtr++];
            }
            else
            {
                xtemp = x - (y >> i);
                y = y + (x >> i);
                x = xtemp;
                theta -= arctanTable[arctanPtr++];
            }
        }

        // set result in point
        p.m_x = x;
        p.m_y = y;
    }


    /**
     * Compute (r,theta) for the prenormalized vector based on the cordic algorithm
     * @param p On entry contains (x,y), on exit contains (r,theta)
     */
    private static void CordicPolarize(Point2D p)
    {
        long theta, yi;
        long x, y;
        int arctanPtr;

        // get the vector to the right half-plane
        x = p.m_x;
        y = p.m_y;
        theta = 0;
        if (x < 0)
        {
            x = -x;
            y = -y;
            theta = 2*QUARTER;
        }
        if (y > 0)
            theta = -theta;

        // initial rotation done with left shift
        arctanPtr = 0;
        if (y < 0)  // rotate positive
        {
            yi = y + (x << 1);
            x = x - (y << 1);
            y = yi;
            theta -= arctanTable[arctanPtr++];  // substract angle
        }
        else    // rotate negative
        {
            yi = y - (x << 1);
            x = x + (y << 1);
            y = yi;
            theta += arctanTable[arctanPtr++];  // add angle
        }

        // next rotations are done with right shifts
        for(int i=0 ; i < MAX_ITERATIONS ; i++)
        {
            if (y < 0)  // rotate positive
            {
                yi = y + (x >> i);
                x = x - (y >> i);
                y = yi;
                theta -= arctanTable[arctanPtr++];  // substract angle
            }
            else    // rotate negative
            {
                yi = y - (x >> i);
                x = x + (y >> i);
                y = yi;
                theta += arctanTable[arctanPtr++];  // add angle
            }
        }

        // set result in point (x becomes r and y hecomes theta)
        p.m_x = x;
        p.m_y = theta;
    }


    /**
     * Multiply fractional part of the two numbers, assuming their are fixed point
     * with 16 fractional bits
     * @param a
     * @param b
     * @return The fractional part of the multiplication
     */
    private static long FractionMultiply(long a, long b)
    {
        return ((a >> 15)*(b >> 15));
    }

}