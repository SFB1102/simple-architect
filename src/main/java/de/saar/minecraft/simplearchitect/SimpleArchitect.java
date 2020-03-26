package de.saar.minecraft.simplearchitect;

import com.google.common.collect.Iterables;
import de.saar.coli.minecraft.MinecraftRealizer;
import de.saar.coli.minecraft.relationextractor.Block;
import de.saar.coli.minecraft.relationextractor.MinecraftObject;
import de.saar.coli.minecraft.relationextractor.Relation;
import de.saar.coli.minecraft.relationextractor.Relation.Orientation;
import de.saar.coli.minecraft.relationextractor.UniqueBlock;
import de.saar.minecraft.architect.AbstractArchitect;
import de.saar.minecraft.shared.BlockDestroyedMessage;
import de.saar.minecraft.shared.BlockPlacedMessage;
import de.saar.minecraft.shared.StatusMessage;
import de.saar.minecraft.shared.TextMessage;
import de.saar.minecraft.shared.WorldSelectMessage;
import de.up.ling.irtg.algebra.ParserException;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import umd.cs.shop.JSJshop;
import umd.cs.shop.JSState;
import umd.cs.shop.JSPredicateForm;
import umd.cs.shop.JSTerm;
import umd.cs.shop.JSPlan;
import umd.cs.shop.JSTaskAtom;
import umd.cs.shop.CostFunction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.Math.abs;

public class SimpleArchitect extends AbstractArchitect {
    private static Logger logger = LogManager.getLogger(SimpleArchitect.class);
    private Block lastBlock;
    private List<Block> plan;
    private Set<MinecraftObject> world;
    private MinecraftRealizer realizer;
    private AtomicInteger numInstructions = new AtomicInteger(0);
    private AtomicLong lastUpdate = new AtomicLong(0);
    private String currentInstruction;
    private Orientation lastOrientation = Orientation.ZPLUS;
    private String scenario;

    //enum InstructionLevel{
    //    BLOCK,
    //    MEDIUM,
    //    HIGHLEVEL
    //}

    public SimpleArchitect(int waitTime) {
        int mctsruns = 10000; //number of runs the planner tries to do
        int timeout = 10000; //time the planner runs in ms
        JSJshop planner = new JSJshop();
        var initialworld = SimpleArchitect.class.getResourceAsStream("/de/saar/minecraft/worlds/house.csv");
        var domain = SimpleArchitect.class.getResourceAsStream("/de/saar/minecraft/domains/house-block.lisp");
        // String bridgeFancy = "build-bridge 3 66 1 4 3 3";
        //String bridgeSimple= "build-bridge 3 66 1 3 5 2";
        // for simple bridge height is height of banister, for bridge fancy height of arch
        // String house = "build-house x y z width length height"
        String problem = "build-house 3 66 3 4 4 3";
        CostFunction.InstructionLevel level = CostFunction.InstructionLevel.BLOCK;
        JSPlan jshopPlan = planner.nlgSearch(mctsruns,timeout, initialworld, problem, domain, level);

        this.realizer = MinecraftRealizer.createRealizer();
        this.plan = new ArrayList<>();
        //world = new HashSet<>();
        //world.add(new UniqueBlock("blue", 3, 3, 3));
        world = transformState(planner.prob.state());
        transformPlan(jshopPlan);
        currentInstruction = generateResponse();
    }

    public SimpleArchitect() {
        this(1000);
    }

    @Override
    public void initialize(WorldSelectMessage message) {
        setGameId(message.getGameId());
        scenario = message.getName();
    }

    @Override
    public void setMessageChannel(StreamObserver<TextMessage> messageChannel) {
        super.setMessageChannel(messageChannel);
        sendMessage("Welcome! I will try to instruct you to build a " + scenario);
    }

    public void transformPlan(JSPlan jshopPlan){
        JSTaskAtom t;
        for (short i = 0; i < jshopPlan.size(); i++) {
            t = (JSTaskAtom) jshopPlan.elementAt(i);
            String task = t.toStr().toString();
            String[] taskArray = task.split(" ");
            int x = (int) Double.parseDouble(taskArray[1]);
            int y = (int) Double.parseDouble(taskArray[2]);
            int z = (int) Double.parseDouble(taskArray[3]);
            this.plan.add(new Block(x,y,z));
        }
    }

    public HashSet<MinecraftObject> transformState(JSState state){
        HashSet<MinecraftObject> set = new HashSet();
        for(JSPredicateForm term : state.atoms()){
            String name = (String) term.elementAt(0);
            if(!name.equals("block-at")){
                //System.out.println("Not a block: " + term.toString());
                continue;
            }
            JSTerm data = (JSTerm) term.elementAt(1);
            String type = (String) data.elementAt(0);
            JSTerm tmp = (JSTerm) term.elementAt(2);
            int x = (int) Double.parseDouble(tmp.toStr().toString());
            tmp = (JSTerm) term.elementAt(3);
            int y = (int) Double.parseDouble(tmp.toStr().toString());;
            tmp = (JSTerm) term.elementAt(4);
            int z = (int) Double.parseDouble(tmp.toStr().toString());;
            //System.out.println("Block: " + type + " " + x + " " + y + " "+ z);
            set.add(new UniqueBlock(type, x, y, z));
        }
        return set;
    }

    @Override
    public void handleBlockPlaced(BlockPlacedMessage request) {
        int type = request.getType();
        int gameId = request.getGameId();
        int currNumInstructions = numInstructions.incrementAndGet();
        var textMessageBuilder = TextMessage.newBuilder().setGameId(gameId);

        synchronized (realizer) {
            if (currNumInstructions < numInstructions.get()) {
                // other instructions were planned after this one,
                // our information is outdated, so skip this one
                return;
            }
            lastUpdate.set(java.lang.System.currentTimeMillis());
            int x = request.getX();
            int y = request.getY();
            int z = request.getZ();
            String response = "";
            if (plan.isEmpty()) {
                response = "you are done, no more changes neeeded!";
                // textMessageBuilder.set
            } else {
                var currBlock = plan.get(0);
                if (currBlock.xpos == x
                        // && currBlock.ypos == y
                        && currBlock.zpos == z) {
                    // make the block respond to "it"
                    world.add(currBlock);
                    lastBlock = currBlock;
                    plan.remove(0);
                    currentInstruction = generateResponse();
                    response = "Great! now " + currentInstruction;
                } else {
                    response = String.format("you put a block on (%d, %d, %d) but we wanted a block on (%d, %d, %d)",
                            x, y, z,
                            currBlock.xpos, currBlock.ypos, currBlock.zpos);
                }
            }
            assert !response.equals("");
            textMessageBuilder.setText(response);
            TextMessage mText = textMessageBuilder.build();
            // send the text message back to the client
            messageChannel.onNext(mText);
        }
    }

    @Override
    public void handleBlockDestroyed(BlockDestroyedMessage request) {
        // TODO add logic to only say this if the previous block was correct
        int gameId = request.getGameId();
        messageChannel.onNext(TextMessage
                .newBuilder()
                .setGameId(gameId)
                .setText("Please add this block again :-(")
                .build());
    }

    @Override
    public void handleStatusInformation(StatusMessage request) {
        var x = request.getXDirection();
        var z = request.getZDirection();
        Orientation newOrientation;
        if (abs(x) > abs(z)) {
            // User looks along X-axis
            if (x>0) {
                newOrientation = Orientation.XPLUS;
            } else {
                newOrientation = Orientation.XMINUS;
            }
        } else {
            // looking along Z axis
            if (z>0) {
                newOrientation = Orientation.ZPLUS;
            } else {
                newOrientation = Orientation.ZMINUS;
            }
        }

        boolean orientationStayed = newOrientation == lastOrientation;
        lastOrientation = newOrientation;

        // only re-send the current instruction after five seconds.
        if (orientationStayed && lastUpdate.get() + 5000 > java.lang.System.currentTimeMillis()) {
            return;
        }
        if (!orientationStayed) {
            currentInstruction = generateResponse();
        }
        lastUpdate.set(java.lang.System.currentTimeMillis());
        sendMessage(currentInstruction);
    }


    @Override
    public String getArchitectInformation() {
        return "SimpleArchitect";
    }

    public String generateResponse() {
        if (plan.isEmpty()) {
            return "Congratulations, you are done building a " + "";
        }
        var response = "";
        var target = plan.get(0);
        var relations = Relation.generateAllRelationsBetweeen(
                Iterables.concat(world,
                        org.eclipse.collections.impl.factory.Iterables.iList(target)
                ),
                lastOrientation
        );
        if (lastBlock != null) {
            relations.add(new Relation("it", lastBlock));
        }
        try {
            realizer.setRelations(relations);
            response = realizer.generateStatement("put", target, "type");
        } catch (ParserException e) {
            e.printStackTrace();
        }
        return response;
    }
}
