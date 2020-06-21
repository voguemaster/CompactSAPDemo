import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;
import java.util.Arrays;

/**
 * CompactSAPDemo - a demo of a compact, allocation-free, persistent sweep-and-prune implementation in Java
 */
public class CompactSAPDemo implements Runnable
{
    /// FPS setting
    public static final int FPS = 60;

    /// dummy radius used to convert angles to vectors
    public static final int DUMMY_RADIUS = 256;

    /// image assets, our image "repository" is basically an array of names and IDs
    /// yes, we could have used an enum type with an index member but simplicity wins over unnecessary code anytime
    private BufferedImage[] m_imageRepository;
    private String[] m_imageNamesMap = {
            "/images/small_disc_green.png", "/images/small_disc_red.png",
            "/images/med_disc_green.png", "/images/med_disc_red.png",
            "/images/large_disc_green.png", "/images/large_disc_red.png",
            "/images/huge_disc_green.png", "/images/huge_disc_red.png"
    };
    private static int IMAGE_SMALL_DISC_GREEN = 0;
    private static int IMAGE_SMALL_DISC_RED = 1;
    private static int IMAGE_MED_DISC_GREEN = 2;
    private static int IMAGE_MED_DISC_RED = 3;
    private static int IMAGE_LARGE_DISC_GREEN = 4;
    private static int IMAGE_LARGE_DISC_RED = 5;
    private static int IMAGE_HUGE_DISC_GREEN = 6;
    private static int IMAGE_HUGE_DISC_RED = 7;
    private static int IMAGE_LAST = 8;


    /// PRNG to randomize screen locationns and directions
    private Random m_rng;

    /// UI
    private JFrame m_mainFrame;
    private BufferStrategy m_bufferStrategy;
    private int m_renderWidth, m_renderHeight;

    /// main loop flag
    private boolean m_running;

    /// FPS calculation
    private int m_FPS=0;
    private int m_prevSecondFPS=0;
    private long m_lastFPSTick=0;


    /// game entities (the number of entities of each kind is a percentage of the total)
    public final static int NUM_ENTITIES_IN_DEMO = 2000;
    private GameEntity[] m_entities;

    /// collision groups
    public final static short COL_NORMAL = 0x01;    // we only have a normal group really..

    /// broadphase
    private CollisionDetectionSAP m_collisionDetection;

    /// nearphase list of entities in collision
    //private int m_nearphaseColliders;
    //private NearphaseCollisionComparator m_nearphaseComparator;

    /// display bounding rectangles
    private boolean m_bDisplayBV = false;


    /**
     * main entry point
     * @param args
     */
    public static void main(String[] args)
    {
        CompactSAPDemo demo = new CompactSAPDemo();
        demo.init();
    }


    /**
     * main entry point
     */
    public void init()
    {
        // initialize PRNG
        m_rng = new Random(System.nanoTime());

        // collision broadphase
        m_collisionDetection = new CollisionDetectionSAP();

        // collision nearphase list. uncomment this to use the sorted code.
        //m_nearphaseColliders = 0;
        //m_nearphaseComparator = new NearphaseCollisionComparator();

        // init UI stuff
        initUI();

        // initialize assets, we dont have that many...
        initAssets();

        // create entities
        initEntities();

        // create the main thread
        m_running = true;
        Thread gameThread = new Thread(this);
        gameThread.start();
    }


    /**
     * initialize main frame. ideally that's all we'll need
     */
    private void initUI()
    {
        // set look and feel
        try {
            String lookAndFeel = UIManager.getSystemLookAndFeelClassName();
            UIManager.setLookAndFeel(lookAndFeel);
        } catch(Exception e) {
            e.printStackTrace();
        }

        // create frame on default screen
        GraphicsDevice defaultDevice = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        GraphicsConfiguration gc = defaultDevice.getDefaultConfiguration();
        m_mainFrame = new JFrame("CompactSAPDemo", gc);
        m_mainFrame.setIgnoreRepaint(true);
        m_mainFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        m_mainFrame.setResizable(false);

        // Add ESC listener to quit...
        m_mainFrame.addKeyListener( new KeyAdapter() {
            public void keyPressed( KeyEvent e ) {
                if( e.getKeyCode() == KeyEvent.VK_ESCAPE )
                {
                    // set running to false
                    m_running = false;
                    m_mainFrame.dispose();
                }
            }
        });

        // create a canvas to be used with BufferStrategy. yes, its Canvas + JFrame
        Canvas canvas = new Canvas();
        canvas.setIgnoreRepaint(true);
        Rectangle bounds = gc.getBounds();
        canvas.setSize(bounds.width-100, bounds.height-100);

        // make it go
        m_mainFrame.add(canvas);
        m_mainFrame.pack();
        m_mainFrame.setVisible(true);

        // check final sizes
        m_renderWidth = canvas.getWidth();
        m_renderHeight = canvas.getHeight();

        // create our buffer strategy (after the frame is visible)
        canvas.createBufferStrategy(2);
        m_bufferStrategy = canvas.getBufferStrategy();
    }


    /**
     * read assets from files/resources. the way the project is structured is good both from running inside
     * the IDE and from a JAR executable
     */
    private void initAssets()
    {
        // create a new image "repo" and fill it (order not important)
        m_imageRepository = new BufferedImage[IMAGE_LAST];
        loadImage(IMAGE_SMALL_DISC_GREEN);
        loadImage(IMAGE_SMALL_DISC_RED);
        loadImage(IMAGE_MED_DISC_GREEN);
        loadImage(IMAGE_MED_DISC_RED);
        loadImage(IMAGE_LARGE_DISC_GREEN);
        loadImage(IMAGE_LARGE_DISC_RED);
        loadImage(IMAGE_HUGE_DISC_GREEN);
        loadImage(IMAGE_HUGE_DISC_RED);
    }


    /**
     * create a new entities array and fill it with new entities based on a distribution of:
     * 0.5 small, 0.2 medium/large, 0.1 huge
     */
    private void initEntities()
    {
        m_entities = new GameEntity[NUM_ENTITIES_IN_DEMO];
        int entities = 0;

        // create small entities (10x10 frame)
        int smallNum = (int)(0.3f * NUM_ENTITIES_IN_DEMO);
        for(int i=0 ; i < smallNum ; i++, entities++)
        {
            GameEntity entity = new GameEntity();
            entity.m_radius = 5;
            entity.m_fwidth = entity.m_fheight = 10;
            entity.m_image = loadImage(IMAGE_SMALL_DISC_GREEN);
            entity.m_collisionImage = loadImage(IMAGE_SMALL_DISC_RED);

            // set velocities and directions
            entity.m_velocity = 30;
            initDirectionVector(entity);

            // init coldet stuff - logical width and height, etc.
            BroadphaseEntity proxy = entity.m_broadphaseProxy;
            proxy.m_width = entity.m_fwidth << 3;
            proxy.m_height = entity.m_fheight << 3;
            proxy.m_filterGroup = proxy.m_filterMask = COL_NORMAL;

            // randomize on screen (minus radius*2, upper left of AABB)
            int w = m_renderWidth - 10;
            int h = m_renderHeight - 10;
            int x = (int)(m_rng.nextFloat() * w);
            int y = (int)(m_rng.nextFloat() * h);
            proxy.m_x = x << 3;
            proxy.m_y = y << 3;

            m_entities[entities] = entity;
        }

        // create medium entities (20x20 frame)
        int mediumNum = (int)(0.4f * NUM_ENTITIES_IN_DEMO);
        for(int i=0 ; i < mediumNum ; i++, entities++)
        {
            GameEntity entity = new GameEntity();
            entity.m_radius = 10;
            entity.m_fwidth = entity.m_fheight = 20;
            entity.m_image = loadImage(IMAGE_MED_DISC_GREEN);
            entity.m_collisionImage = loadImage(IMAGE_MED_DISC_RED);

            // set velocities (and directions)
            entity.m_velocity = 30;
            initDirectionVector(entity);

            // init coldet stuff - logical width and height, etc.
            BroadphaseEntity proxy = entity.m_broadphaseProxy;
            proxy.m_width = entity.m_fwidth << 3;
            proxy.m_height = entity.m_fheight << 3;
            proxy.m_filterGroup = proxy.m_filterMask = COL_NORMAL;

            // randomize on screen (minus radius*2, upper left of AABB)
            int w = m_renderWidth - 20;
            int h = m_renderHeight - 20;
            int x = (int)(m_rng.nextFloat() * w);
            int y = (int)(m_rng.nextFloat() * h);
            proxy.m_x = x << 3;
            proxy.m_y = y << 3;

            m_entities[entities] = entity;
        }

        // create large entities (50x50 frame)
        int largeNum = (int)(0.27f * NUM_ENTITIES_IN_DEMO);
        for(int i=0 ; i < largeNum ; i++, entities++)
        {
            GameEntity entity = new GameEntity();
            entity.m_radius = 25;
            entity.m_fwidth = entity.m_fheight = 50;
            entity.m_image = loadImage(IMAGE_LARGE_DISC_GREEN);
            entity.m_collisionImage = loadImage(IMAGE_LARGE_DISC_RED);

            // set velocities and directions
            entity.m_velocity = 20;
            initDirectionVector(entity);

            // init coldet stuff - logical width and height, etc.
            BroadphaseEntity proxy = entity.m_broadphaseProxy;
            proxy.m_width = entity.m_fwidth << 3;
            proxy.m_height = entity.m_fheight << 3;
            proxy.m_filterGroup = proxy.m_filterMask = COL_NORMAL;

            // randomize on screen (minus radius*2, upper left of AABB)
            int w = m_renderWidth - 50;
            int h = m_renderHeight - 50;
            int x = (int)(m_rng.nextFloat() * w);
            int y = (int)(m_rng.nextFloat() * h);
            proxy.m_x = x << 3;
            proxy.m_y = y << 3;

            m_entities[entities] = entity;
        }

        // create hugh entities (80x80 frame)
        int hughNum = NUM_ENTITIES_IN_DEMO - entities;
        for(int i=0 ; i < hughNum ; i++, entities++)
        {
            GameEntity entity = new GameEntity();
            entity.m_radius = 40;
            entity.m_fwidth = entity.m_fheight = 80;
            entity.m_image = loadImage(IMAGE_HUGE_DISC_GREEN);
            entity.m_collisionImage = loadImage(IMAGE_HUGE_DISC_RED);

            // set velocities and directions
            entity.m_velocity = 20;
            initDirectionVector(entity);

            // init coldet stuff - logical width and height, etc.
            BroadphaseEntity proxy = entity.m_broadphaseProxy;
            proxy.m_width = entity.m_fwidth << 3;
            proxy.m_height = entity.m_fheight << 3;
            proxy.m_filterGroup = proxy.m_filterMask = COL_NORMAL;

            // randomize on screen (minus radius*2, upper left of AABB)
            int w = m_renderWidth - 80;
            int h = m_renderHeight - 80;
            int x = (int)(m_rng.nextFloat() * w);
            int y = (int)(m_rng.nextFloat() * h);
            proxy.m_x = x << 3;
            proxy.m_y = y << 3;

            m_entities[entities] = entity;
        }

        // add all entities to the broadphase. in a normal game, game objects dont enter all at once (except world geometry)
        // the reason we care is that adding many objects together one at a time is not efficient as it could be but in our case its ok.
        for(int i=0 ; i < m_entities.length ; i++)
        {
            m_collisionDetection.addEntity(m_entities[i].m_broadphaseProxy, true);
        }
    }


    /**
     * randomize a direction angle and convert to a direction vector
     * @param entity
     */
    private void initDirectionVector(GameEntity entity)
    {
        // randomize an angle from 0 to 359 and create a direction vector for it
        int angle = Math.abs(m_rng.nextInt() % 360) << MathHelper.FRACTIONAL_BITS;
        convertAngleToVector(angle, entity.m_dirVector);
        entity.m_dirNorm = DUMMY_RADIUS;    // since the conversion uses a DUMMY_RADIUS length
    }


    /**
     * Obtain a direction vector given an angle in 16 bit fractional, representing the heading we
     * are facing. The vector is calculated by rotating a line of DUMMY_RADIUS with the angle wanted
     * @param angle The angle in our convention
     * @param vec On exit, dx and dy of the vector
     */
    private void convertAngleToVector(long angle, Point2D vec)
    {
        vec.m_x = DUMMY_RADIUS << MathHelper.FRACTIONAL_BITS;
        vec.m_y = 0;
        MathHelper.Rotate(vec, angle);
        vec.m_x = vec.m_x >> MathHelper.FRACTIONAL_BITS;
        vec.m_y = vec.m_y >> MathHelper.FRACTIONAL_BITS;
    }


    /**
     * loads or returns a cached image
     * @param imageID
     * @return
     */
    private BufferedImage loadImage(int imageID)
    {
        BufferedImage[] images = m_imageRepository;
        BufferedImage image = null;
        if (images[imageID] != null)
        {
            image = images[imageID];
        }
        else
        {
            // attempt to load it from resources
            InputStream is = getClass().getResourceAsStream(m_imageNamesMap[imageID]);
            try
            {
                image = ImageIO.read(is);
                is.close();
            } catch(IOException ioe)
            {
                ioe.printStackTrace();
                image = null;
            }

            // cache it
            images[imageID] = image;
        }

        return image;
    }


    /**
     * main loop implementation
     */
    public void run()
    {
        long maxFrameDuration = 1000 / FPS;  // ok to be int (this is in millis)
        m_lastFPSTick = System.nanoTime();
        while(m_running)
        {
            long frameStart = System.nanoTime();

            // update the scene/world
            frameUpdate(frameStart);

            // enforce FPS-like behavior. sleep to ensure rate
            long frameEnd = System.nanoTime();
            long frameDuration = (frameEnd-frameStart)/1000000;    // could be avoided with currentTimeMillis or uptimeMillis
            //System.out.println("frame time: " + (double)(frameEnd-frameStart) / (double)1000000.0);

            // calculate FPS
            if (frameEnd-m_lastFPSTick >= 1000000000)
            {
                // one second has passed, record FPS
                m_lastFPSTick = frameEnd;
                m_FPS = m_prevSecondFPS;
                m_prevSecondFPS = 0;
            }
            else
            {
                m_prevSecondFPS++;  // one more frame in this second
            }

            if (frameDuration < maxFrameDuration)
            {
                try
                {
                    Thread.sleep(maxFrameDuration-frameDuration);   //  this is in ms
                } catch(InterruptedException ie) { ie.printStackTrace(); }
            }
        }

        // cleanup stuff
        m_imageRepository = null;
    }


    /**
     * master update method - will update all entities in the world. update their motion and the collision system
     * @param tick the frame start time
     */
    private void frameUpdate(long tick)
    {
        updateMotion(tick);

        // update entity's bounds in the broadphase
        int entities = m_entities.length;
        for(int i=0 ; i < entities ; i++)
        {
            m_collisionDetection.updateEntityBounds(m_entities[i].m_broadphaseProxy);
        }

        // resolve collisions
        resolveCollisions();

        // keep the entities within the view
        bounceOffWalls();

        // render everything
        render(tick);

        // clear the state of the nearphase collisions
        clearCollisions();
    }


    /**
     * update motion for everyone
     * @param tick
     */
    private void updateMotion(long tick)
    {
        // update motion for all entities
        int entities = m_entities.length;
        for(int i=0 ; i < entities ; i++)
        {
            GameEntity entity = m_entities[i];
            BroadphaseEntity proxy = entity.m_broadphaseProxy;

            // calculate new position. simple integration is ok for us and we can ignore the exact delta time (of a frame) for now..
            // we perform the calculation in 16 bit fixed point
            Point2D dirVec = entity.m_dirVector;
            int dirNorm = entity.m_dirNorm;
            int ds = entity.m_velocity << MathHelper.FRACTIONAL_BITS;

            // add this distance to our current position (Xi = Xi-1 + d/norm(d) * ds)
            int dx = ((int)dirVec.m_x * ds / dirNorm) >> MathHelper.FRACTIONAL_BITS;
            int dy = ((int)dirVec.m_y * ds / dirNorm) >> MathHelper.FRACTIONAL_BITS;
            proxy.m_x += dx;
            proxy.m_y += dy;
        }
    }


    /**
     * keep the discs within the view
     */
    private void bounceOffWalls()
    {
        // resolve collision with the walls. yes, technically having invisible walls surround the view (thus handling the collision in the
        // general case) is the best approach but this isn't the focus of this demo
        int entities = m_entities.length;
        for(int i=0 ; i < entities ; i++)
        {
            GameEntity entity = m_entities[i];
            BroadphaseEntity proxy = entity.m_broadphaseProxy;

            // find center and check against bounds of the view (if your proxy's AABB is smaller/larger than the graphics frame width this calculation should be different)
            int rwidth = m_renderWidth;
            int rheight = m_renderHeight;
            int cx = (proxy.m_x + (proxy.m_width >> 1)) >> 3;
            int cy = (proxy.m_y + (proxy.m_height >> 1)) >> 3;
            if (cx < 0)
            {
                proxy.m_x -= cx;    // get back the same amount as the penetration depth into the wall
                entity.m_dirVector.m_x = -entity.m_dirVector.m_x;
            }
            else if (cx > rwidth)
            {
                proxy.m_x -= (cx-rwidth);
                entity.m_dirVector.m_x = -entity.m_dirVector.m_x;
            }
            if (cy < 0)
            {
                proxy.m_y -= cy;
                entity.m_dirVector.m_y = -entity.m_dirVector.m_y;
            }
            else if (cy > rheight)
            {
                proxy.m_y -= (cy-rheight);
                entity.m_dirVector.m_y = -entity.m_dirVector.m_y;
            }
        }
    }


    /**
     * obtain all overlapping pairs from the collision broadphase and determine nearphase collision and result. We have several methods, some wont work
     * for our wanted behavior:
     * 1. Using a simple flag to signal collision per entity is not enough. its possible we iterated pairs and found A and C to intersect (thus setting true)
     *    but then find another pair with C and E in which they are not really intersecting. In this case we'll clear the flag which is incorrect.
     * 2. Use a counter per entity. However, it is possible that entities will no longer be intersecting (when the SAP detects the pair's AABBs are no longer overlapping)
     *    but the counter is still above 0. This sounds like a good idea but actually it poses a problem - we have to process each collision only once (otherwise the counter
     *    might get incremented each frame). If we disallow anymore collision for the pair of entities we prevent them from being found in collision with other entities.
     * 3. Collect only the intersecting pairs in an array that is cleared each frame. that way we really only have the subset of entities that are in nearphase collision.
     *    We can implement a way not to add an entity twice into the array but that costs more than just adding it already.
     *    We'll set a flag that allows us to render them appropriately later (without drawing them twice). this flag is used to sort the entities in the array so that intersecting
     *    entities are at the end.
     */
    /*private void resolveCollisionsSorted()
    {
        // clear neaphase list
        m_nearphaseColliders = 0;

        // obtain overlapping pairs
        int numPairs = m_collisionDetection.getPairsCount();
        if (numPairs > 0)
        {
            boolean bHasNearphase = false;

            // obtain overlapping pair "structs"
            int[] pairManager = m_collisionDetection.getOverlappingPairs();
            for(int pairID=0 ; pairID < numPairs ; pairID++)
            {
                // obtain entities from pair
                int pair = pairManager[pairID];
                BroadphaseEntity entityA = m_collisionDetection.getFirstEntityFromPair(pair);
                BroadphaseEntity entityB = m_collisionDetection.getSecondEntityFromPair(pair);

                // for our simple purpose we'd now like to test intersection between the discs. if they are found to intersect
                // we'll set the nearphase flag and this will color them appropriately
                int cxA = entityA.m_x + (entityA.m_width >> 1);
                int cyA = entityA.m_y + (entityA.m_height >> 1);
                int cxB = entityB.m_x + (entityB.m_width >> 1);
                int cyB = entityB.m_y + (entityB.m_height >> 1);

                // finding the radii is also a simplification since we know how the logical AABB was calculated. we just divide the width by 2
                // we add to the counter if they are found to be intersecting and subtract from it when they are not intersecting (remember their AABBs still overlap but they may not intersect)
                int rA = entityA.m_width >> 1;
                int rB = entityB.m_height >> 1;
                int rad = rA+rB;
                int dx = cxA-cxB;
                int dy = cyA-cyB;
                if (dx*dx + dy*dy <= rad*rad)
                {
                    // set their flag
                    entityA.m_nearphaseCollision = entityB.m_nearphaseCollision = 1;
                    bHasNearphase = true;
                }
            }

            // if we have nearphase collisions we'll want to sort the entities array so that we dont have to draw all of them and then draw the colliders
            // list again (which introduces overdraw). this also solves having to use a conditional in the render method to determine if to use the normal image or collision image
            if (bHasNearphase)
            {
                Arrays.sort(m_entities, m_nearphaseComparator);

                // count nearphase colliders
                int entities = m_entities.length;
                int colliders = 0;
                for(int i=0 ; i < entities ; i++)
                {
                    BroadphaseEntity proxy = m_entities[i].m_broadphaseProxy;
                    colliders += proxy.m_nearphaseCollision;
                    proxy.m_nearphaseCollision = 0;     // no longer needed
                }
                m_nearphaseColliders = colliders;
            }
        }
    }*/


    /**
     * obtain all overlapping pairs from the collision broadphase and determine nearphase collision and result. We have several methods, some wont work
     * for our wanted behavior:
     * 1. Using a simple flag to signal collision per entity is not enough. its possible we iterated pairs and found A and C to intersect (thus setting true)
     *    but then find another pair with C and E in which they are not really intersecting. In this case we'll clear the flag which is incorrect.
     * 2. Use a counter per entity. However, it is possible that entities will no longer be intersecting (when the SAP detects the pair's AABBs are no longer overlapping)
     *    but the counter is still above 0. This sounds like a good idea but actually it poses a problem - we have to process each collision only once (otherwise the counter
     *    might get incremented each frame). If we disallow anymore collision for the pair of entities we prevent them from being found in collision with other entities.
     * 3. Collect only the intersecting pairs in an array that is cleared each frame. that way we really only have the subset of entities that are in nearphase collision.
     *    We can implement a way not to add an entity twice into the array but that costs more than just adding it already.
     *    We'll set a flag that allows us to render them appropriately later (without drawing them twice). this flag is used to sort the entities in the array so that intersecting
     *    entities are at the end.
     */
    private void resolveCollisions()
    {
        // clear neaphase list
        //m_nearphaseColliders = 0;

        // obtain overlapping pairs
        int numPairs = m_collisionDetection.getPairsCount();
        if (numPairs > 0)
        {
            // obtain overlapping pair "structs"
            int[] pairManager = m_collisionDetection.getOverlappingPairs();
            for(int pairID=0 ; pairID < numPairs ; pairID++)
            {
                // obtain entities from pair
                int pair = pairManager[pairID];
                BroadphaseEntity entityA = m_collisionDetection.getFirstEntityFromPair(pair);
                BroadphaseEntity entityB = m_collisionDetection.getSecondEntityFromPair(pair);

                // for our simple purpose we'd now like to test intersection between the discs. if they are found to intersect
                // we'll set the nearphase flag and this will color them appropriately
                int cxA = entityA.m_x + (entityA.m_width >> 1);
                int cyA = entityA.m_y + (entityA.m_height >> 1);
                int cxB = entityB.m_x + (entityB.m_width >> 1);
                int cyB = entityB.m_y + (entityB.m_height >> 1);

                // finding the radii is also a simplification since we know how the logical AABB was calculated. we just divide the width by 2
                // we add to the counter if they are found to be intersecting and subtract from it when they are not intersecting (remember their AABBs still overlap but they may not intersect)
                int rA = entityA.m_width >> 1;
                int rB = entityB.m_height >> 1;
                int rad = rA+rB;
                int dx = cxA-cxB;
                int dy = cyA-cyB;
                if (dx*dx + dy*dy <= rad*rad)
                {
                    // set their flag
                    entityA.m_nearphaseCollision = entityB.m_nearphaseCollision = 1;
                }
            }
        }
    }


    /**
     * clear the state of the nearphase collision flag for all entities. aince we are only interested in knowing if an object is in nearphase
     * collision with another object for display purposes we don't need cohesion. We need a way to clean the state off the objects once they are
     * properly rendered.
     */
    private void clearCollisions()
    {
        // clear up nearphase state for the objects
        int entities = m_entities.length;
        for(int i=0 ; i < entities ; i++)
        {
            BroadphaseEntity proxy = m_entities[i].m_broadphaseProxy;
            proxy.m_nearphaseCollision = 0;
        }
    }


    /**
     * render all entities
     * @param tick the frame start time
     */
    /*private void renderSorted(long tick)
    {
        // obtain the graphics context of the strategy
        Graphics2D g2d = (Graphics2D)m_bufferStrategy.getDrawGraphics();

        // our background is simple enough
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, m_renderWidth, m_renderHeight);

        // draw entities not in collision
        int entities = m_entities.length-m_nearphaseColliders;
        for(int i=0 ; i < entities ; i++)
        {
            GameEntity entity = m_entities[i];
            BroadphaseEntity proxy = entity.m_broadphaseProxy;
            int x = proxy.m_x >> 3;
            int y = proxy.m_y >> 3;
            g2d.drawImage(entity.m_image, x, y, null);
        }

        // draw nearphase colliding entities in their respective graphic. this introduces overdraw since we draw them again. if we wanted to optimize
        // this we'd need to keep two lists - colliders and non-colliders, or put them in one array and sort them
        entities = m_entities.length;
        for(int i=entities-m_nearphaseColliders ; i < entities ; i++)
        {
            GameEntity entity = m_entities[i];
            BroadphaseEntity proxy = entity.m_broadphaseProxy;
            int x = proxy.m_x >> 3;
            int y = proxy.m_y >> 3;
            g2d.drawImage(entity.m_collisionImage, x, y, null);
        }

        // display bounding volumes, if wanted
        if (m_bDisplayBV)
        {
            for(int i=0 ; i < entities ; i++)
            {
                GameEntity entity = m_entities[i];
                BroadphaseEntity proxy = entity.m_broadphaseProxy;
                int x = proxy.m_x >> 3;
                int y = proxy.m_y >> 3;
                int w = proxy.m_width >> 3;
                int h = proxy.m_height >> 3;
                Color bvColor = proxy.inBroadphaseCollision() ? Color.RED : Color.BLACK;  // we can probably optimize away this conditional
                g2d.setPaint(bvColor);
                g2d.drawRect(x, y, w, h);
            }
        }

        // draw FPS
        g2d.setColor(Color.BLUE);
        g2d.drawString("FPS: "+m_FPS, 20, 20);

        g2d.dispose();

        // make it show (try to think why it would be bad for the game loop to handle contentsLost/Restored in a while loop here)
        m_bufferStrategy.show();
    }*/


    /**
     * render all entities
     * @param tick the frame start time
     */
    private void render(long tick)
    {
        // obtain the graphics context of the strategy
        Graphics2D g2d = (Graphics2D)m_bufferStrategy.getDrawGraphics();

        // our background is simple enough
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, m_renderWidth, m_renderHeight);

        // draw all the entities. Note the inefficient use of a conditional there. Still is better than performing
        // a sort on all the array and counting the entities in nearphase collision (see resolveCollisionsSorted and renderSorted)
        int entities = m_entities.length;
        for(int i=0 ; i < entities ; i++)
        {
            GameEntity entity = m_entities[i];
            BroadphaseEntity proxy = entity.m_broadphaseProxy;
            int x = proxy.m_x >> 3;
            int y = proxy.m_y >> 3;
            Image img = (proxy.m_nearphaseCollision > 0) ? entity.m_collisionImage : entity.m_image;
            g2d.drawImage(img, x, y, null);
        }

        // display bounding volumes, if wanted
        if (m_bDisplayBV)
        {
            for(int i=0 ; i < entities ; i++)
            {
                GameEntity entity = m_entities[i];
                BroadphaseEntity proxy = entity.m_broadphaseProxy;
                int x = proxy.m_x >> 3;
                int y = proxy.m_y >> 3;
                int w = proxy.m_width >> 3;
                int h = proxy.m_height >> 3;
                Color bvColor = proxy.inBroadphaseCollision() ? Color.RED : Color.BLACK;  // we can probably optimize away this conditional
                g2d.setPaint(bvColor);
                g2d.drawRect(x, y, w, h);
            }
        }

        // draw FPS
        g2d.setColor(Color.BLUE);
        g2d.drawString("FPS: "+m_FPS, 20, 20);

        g2d.dispose();

        // make it show (try to think why it would be bad for the game loop to handle contentsLost/Restored in a while loop here)
        m_bufferStrategy.show();
    }
    
}

