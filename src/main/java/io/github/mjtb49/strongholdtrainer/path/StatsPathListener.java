package io.github.mjtb49.strongholdtrainer.path;

import io.github.mjtb49.strongholdtrainer.api.StartAccessor;
import io.github.mjtb49.strongholdtrainer.api.StrongholdTreeAccessor;
import io.github.mjtb49.strongholdtrainer.commands.NextMistakeCommand;
import io.github.mjtb49.strongholdtrainer.ml.StrongholdMachineLearning;
import io.github.mjtb49.strongholdtrainer.stats.PlayerPathData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.structure.StrongholdGenerator;
import net.minecraft.structure.StructurePiece;
import net.minecraft.util.Pair;
import org.apache.commons.lang3.ArrayUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

// TODO: Cleanup, optimization, thread-safety
public class StatsPathListener implements StrongholdPathListener {

    public static final HashMap<Class<? extends StrongholdGenerator.Piece>, Integer> FEINBERG_AVG_ROOM_TIMES = new HashMap<>();

    static {
        FEINBERG_AVG_ROOM_TIMES.put(StrongholdGenerator.ChestCorridor.class, 25);
        FEINBERG_AVG_ROOM_TIMES.put(StrongholdGenerator.SquareRoom.class, 39);
        FEINBERG_AVG_ROOM_TIMES.put(StrongholdGenerator.PrisonHall.class, 42);
        FEINBERG_AVG_ROOM_TIMES.put(StrongholdGenerator.Stairs.class, 33);
        FEINBERG_AVG_ROOM_TIMES.put(StrongholdGenerator.Corridor.class, 26);
        FEINBERG_AVG_ROOM_TIMES.put(StrongholdGenerator.SpiralStaircase.class, 52);
        FEINBERG_AVG_ROOM_TIMES.put(StrongholdGenerator.FiveWayCrossing.class, 60);
        FEINBERG_AVG_ROOM_TIMES.put(StrongholdGenerator.LeftTurn.class, 20);
        FEINBERG_AVG_ROOM_TIMES.put(StrongholdGenerator.RightTurn.class, 18);
        FEINBERG_AVG_ROOM_TIMES.put(StrongholdGenerator.PortalRoom.class, 0);
        FEINBERG_AVG_ROOM_TIMES.put(StrongholdGenerator.Start.class, 0);
        FEINBERG_AVG_ROOM_TIMES.put(null, 0);
        FEINBERG_AVG_ROOM_TIMES.put(StrongholdGenerator.Library.class, 0);
        FEINBERG_AVG_ROOM_TIMES.put(StrongholdGenerator.SmallCorridor.class, 0);
    }

    double difficulty;
    int wastedTime;
    int wormholeCount;
    int bestMoveCount = 0;
    int inaccuracyCount = 0;
    int mistakeCount = 0;
    int roomsReviewed = 0;
    List<StrongholdGenerator.Piece> mistakes;
    List<StrongholdGenerator.Piece> inaccuracies;
    private StrongholdPath strongholdPath;
    private ServerPlayerEntity playerEntity;
    private boolean completed;

    public StatsPathListener() {
        this.playerEntity = null;
        this.mistakes = new ArrayList<>();
        this.inaccuracies = new ArrayList<>();
    }

    protected static double loss(StrongholdPath path, StrongholdPathEntry entry, ArrayList<StructurePiece> solution) {
        // Tree accessor, previous entry and policy, history
        StrongholdTreeAccessor treeAccessor = (StrongholdTreeAccessor) path.getStart();
        StrongholdPathEntry previousEntry = path.getPrecedingEntry(entry);
        double[] policy = previousEntry.getPolicy();
        List<StrongholdPathEntry> history = path.getHistory();
        int j = history.indexOf(entry);

        // Relevant weights
        double chosenWeight = policy[treeAccessor.getTree().get(previousEntry.getCurrentPiece()).indexOf(entry.getCurrentPiece())];
        if (policy.length == 6) {
            chosenWeight = policy[treeAccessor.getTree().get(previousEntry.getCurrentPiece()).indexOf(entry.getCurrentPiece()) + 1];
        }
        double maxWeight = Collections.max(Arrays.asList(ArrayUtils.toObject(policy)));

        int wastedTickCounter = 0;
        while (!solution.contains(history.get(j).getCurrentPiece())) {
            wastedTickCounter += history.get(j).getTicksSpentInPiece().get();
            j++;
        }
        return (wastedTickCounter) * (maxWeight - chosenWeight);
    }

    protected static boolean validateEntryForLoss(StrongholdPath path, StrongholdPathEntry entry) {
        StrongholdTreeAccessor treeAccessor = (StrongholdTreeAccessor) path.getStart();
        try {
            return path.getPrecedingEntry(entry) != null
                    && path.getPrecedingEntry(entry).getPolicy() != null
                    && entry.getCurrentPiece() != null
                    && entry.getPreviousPiece() != null
                    && path.getNextEntry(entry) != null
                    && treeAccessor.getTree().get(path.getPrecedingEntry(entry).getCurrentPiece()).contains(entry.getCurrentPiece())
                    && entry.getPolicy() != null
                    && !(entry.getCurrentPiece() instanceof StrongholdGenerator.Start);
        } catch (Exception e) {
            return false;
        }

    }

//    public void setAlertingPlayer(ServerPlayerEntity player){
//        this.playerEntity = player;
//    }

    protected static boolean areAdjacent(StrongholdGenerator.Piece piece1, StrongholdGenerator.Piece piece2, StrongholdTreeAccessor strongholdTreeAccessor) {
        if (piece1 == null || piece2 == null || (strongholdTreeAccessor.getTree().get(piece2)) == null || (strongholdTreeAccessor.getTree().get(piece1) == null)) {
            return false;
        }
        return piece1 == (strongholdTreeAccessor.getParents().get(piece2)) ||
                (strongholdTreeAccessor.getTree().get(piece2)).contains(piece1) ||
                piece2 == (strongholdTreeAccessor.getParents().get(piece1)) ||
                (strongholdTreeAccessor.getTree().get(piece1)).contains(piece2);
    }

    @Deprecated
    public void update(boolean completed) {
        this.completed = completed;
        if (this.completed) {
            this.populateStats().updateAndPrintAllStats(playerEntity);
        }
    }

    @Override
    public void update(StrongholdPath.PathEvent event) {
        switch (event) {
            case PATH_UPDATE:
            case OUTSIDE_TICK:
                break;
            case PATH_COMPLETE:

                this.completed = true;
                this.populateStats().updateAndPrintAllStats(playerEntity);
                NextMistakeCommand.submitMistakesAndInaccuracies(getMistakes(), getInaccuracies());
                NextMistakeCommand.sendInitialMessage(playerEntity);
                ((StartAccessor)strongholdPath.getStructureStart()).setHasBeenRouted(true);
                //playerEntity.sendMessage(new LiteralText("splits").styled(style -> style.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, strongholdPath.sendSplits()))), false);
                break;
        }

    }

    @Override
    public void attach(StrongholdPath path) {
        this.strongholdPath = path;
        path.addListener(this);
    }

    @Override
    public void detach() {
        this.strongholdPath.removeListener(this);
        this.strongholdPath = null;
    }

    public ArrayList<StrongholdGenerator.Piece> getMistakes() {
        return new ArrayList<>(mistakes);
    }

    public ArrayList<StrongholdGenerator.Piece> getInaccuracies() {
        return new ArrayList<>(inaccuracies);
    }

    public boolean isCompleted() {
        return completed;
    }

    public PlayerPathData populateStats() {
        this.playerEntity = strongholdPath.getPlayerEntity();
        StrongholdGenerator.Start start = this.strongholdPath.getStart();
        StrongholdTreeAccessor treeAccessor = (StrongholdTreeAccessor) start;

        ArrayList<StructurePiece> solution = new ArrayList<>();
        StrongholdGenerator.Piece current = this.strongholdPath.getHistory().get(strongholdPath.getHistory().size() - 1).getCurrentPiece();
        while (current != null) {
            solution.add(current);
            current = (StrongholdGenerator.Piece) treeAccessor.getParents().get(current);
        }
        List<StrongholdPathEntry> history = this.strongholdPath.getHistory();

        int ticksInStronghold = strongholdPath.getTotalTime();
        this.roomsReviewed = history.stream().map(StrongholdPathEntry::getCurrentPiece).collect(Collectors.toSet()).size() - 1;
        this.wastedTime = history.stream()
                .filter(pathEntry -> !solution.contains(pathEntry.getCurrentPiece()))
                .map(StrongholdPathEntry::getTicksSpentInPiece)
                .mapToInt(AtomicInteger::get)
                .sum();
        // TODO: don't calculate loss twice
        List<StrongholdPathEntry> validEntries = history.stream().filter(entry -> validateEntryForLoss(strongholdPath, strongholdPath.getNextEntry(entry))).collect(Collectors.toList());
        List<StrongholdPathEntry> incorrectDecisions = validEntries.stream()
                .filter(entry -> !solution.contains(strongholdPath.getNextEntry(entry).getCurrentPiece()) && solution.contains(entry.getCurrentPiece()))
                .collect(Collectors.toList());
        List<StrongholdPathEntry> mistakesAndInaccuracies = incorrectDecisions.stream()
                .filter(entry -> loss(strongholdPath, strongholdPath.getNextEntry(entry), solution) >= 100)
                .collect(Collectors.toList());
        List<StrongholdPathEntry> mistakes = mistakesAndInaccuracies.stream()
                .filter(entry -> loss(strongholdPath, strongholdPath.getNextEntry(entry), solution) >= 200)
                .collect(Collectors.toList());
        mistakesAndInaccuracies.removeAll(mistakes);
        this.mistakes = mistakes.stream().map(StrongholdPathEntry::getCurrentPiece).collect(Collectors.toList());
        this.inaccuracies = mistakesAndInaccuracies.stream().map(StrongholdPathEntry::getCurrentPiece).collect(Collectors.toList());
        this.mistakeCount = this.mistakes.size();
        this.inaccuracyCount = this.inaccuracies.size();
        this.bestMoveCount = (int) validEntries.stream()
                .map(entry -> strongholdPath.getNextEntry(entry))
                .map(StrongholdPathEntry::getCurrentPiece)
                .filter(solution::contains)
                .count();
        this.difficulty = computeDifficulty(solution);
        this.wormholeCount = (int) history.stream().filter(entry -> !(entry.getCurrentPiece() instanceof StrongholdGenerator.PortalRoom)).filter(entry -> !areAdjacent(entry.getCurrentPiece(), strongholdPath.getNextEntry(entry).getCurrentPiece(), treeAccessor)).count();

        ArrayList<Pair<StrongholdGenerator.Piece, Integer>> rooms = new ArrayList<>();
        history.forEach(pathEntry -> {
            Pair<StrongholdGenerator.Piece, Integer> pair = new Pair<>(pathEntry.getCurrentPiece(), pathEntry.getTicksSpentInPiece().get());
            rooms.add(pair);
        });
        int tickLossAgainstFeinberg = history.stream().filter(entry -> FEINBERG_AVG_ROOM_TIMES.containsKey(entry.getCurrentPiece().getClass())).mapToInt(value -> value.getTicksSpentInPiece().get() - FEINBERG_AVG_ROOM_TIMES.get(value.getCurrentPiece().getClass())).sum();
        return new PlayerPathData(
                rooms,
                ticksInStronghold,
                difficulty,
                wastedTime,
                bestMoveCount,
                inaccuracyCount,
                mistakeCount,
                wormholeCount,
                roomsReviewed,
                tickLossAgainstFeinberg
        );
    }

    private double computeDifficulty(ArrayList<StructurePiece> solution) {
        double difficulty = 1.0;
        //Loop starts at 2 because the portal room and the room before it have messed up policy but are always trivial
        for (int i = 2; i < solution.size(); i++) {
            //difficulty *= 0.5;
            difficulty *= getWeightOfCorrectDoor(solution, (StrongholdGenerator.Piece) solution.get(i),
                    StrongholdMachineLearning.MODEL_REGISTRY.getModel("basic-classifier-nobacktracking").getPredictions(this.strongholdPath.getStart(), (StrongholdGenerator.Piece) solution.get(i)));

        }
        return difficulty;
    }

    private double getWeightOfCorrectDoor(ArrayList<StructurePiece> solution, StrongholdGenerator.Piece piece, double[] policy) {
        List<StructurePiece> children = ((StrongholdTreeAccessor) this.strongholdPath.getStart()).getTree().get(piece);

        for (int i = 0; i < children.size(); i++) {
            if (solution.contains(children.get(i))) {
                return policy[i];
            }
        }
        //should never run
        return -1;
    }
}

