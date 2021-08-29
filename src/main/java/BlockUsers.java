import gearth.extensions.Extension;
import gearth.extensions.ExtensionInfo;
import gearth.extensions.extra.tools.ChatConsole;
import gearth.extensions.parsers.HEntity;
import gearth.extensions.parsers.HEntityType;
import gearth.extensions.parsers.HGender;
import gearth.misc.Cacher;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.io.File;
import java.net.URISyntaxException;
import java.util.*;
import java.util.stream.Collectors;

@ExtensionInfo(
        Title =  "Block Users",
        Description =  "Remove users from the game",
        Version =  "1.0",
        Author =  "sirjonasxx"
)
public class BlockUsers extends Extension {

    public BlockUsers(String[] args) {
        super(args);
    }

    public static void main(String[] args) {
        new BlockUsers(args).run();
    }

    /* features:
    * - uses 'ignore' feature from habbo (block speech/trade)
    * - block friend requests
    * - block or replace presence in rooms
    *
    * */

    private enum Mode {
        GHOST,
        INVISIBLE
    }


    // lowercased
    private Set<String> blockedUsers = new HashSet<>();
    private Mode mode = Mode.INVISIBLE;

    private Set<Integer> blockedUserRoomIndexes = new HashSet<>();
    private Map<String, Integer> blockedUserIds = new HashMap<>();


    @Override
    protected void initExtension() {
        try {
            Cacher.setCacheDir((new File(BlockUsers.class.getProtectionDomain().getCodeSource().getLocation().toURI()))
                    .getParentFile().toString());
        } catch (URISyntaxException e) {
            e.printStackTrace();
        };

        if (Cacher.getCacheContents().has("MODE")) {
            mode = Cacher.getCacheContents().getString("MODE").equals("Ghost") ? Mode.GHOST : Mode.INVISIBLE;
        }
        List<Object> cachedBlocks = Cacher.getList("BLOCKS");
        if (cachedBlocks != null) {
            for (Object block : cachedBlocks) {
                blockedUsers.add((String)block);
            }
        }

        ChatConsole chatConsole = new ChatConsole(this,
                "Welcome to Block Trollers, the following commands exist: (You may need to reload the room for the changes to take effect)\n" +
                        "\n" +
                        ":block <user>\n" +
                        ":unblock <user>\n" +
                        ":blocklist\n" +
                        ":mode invisible (default)\n" +
                        ":mode ghost");

        chatConsole.onInput(s -> {
            try {
                if (s.startsWith(":block ")) {
                    String user = s.split(" ")[1];
                    blockUser(user);
                    chatConsole.writeOutput("You successfully blocked: " + user, false);
                }
                else if (s.startsWith(":unblock ")) {
                    String user = s.split(" ")[1];
                    unblockUser(user);
                    chatConsole.writeOutput("You successfully unblocked: " + user, false);
                }
                else if (s.equals(":blocklist")) {
                    chatConsole.writeOutput("You blocked the following users: \n" +
                            "\n" +
                            blockedUsers.stream().map(s1 -> "* " + s1).collect(Collectors.joining("\n")),
                            false);
                }
                else if (s.equals(":mode invisible")) {
                    mode = Mode.INVISIBLE;
                    chatConsole.writeOutput("Set mode to \"Invisible\"", false);
                    Cacher.put("MODE", "Invisible");
                }
                else if (s.equals(":mode ghost")) {
                    mode = Mode.GHOST;
                    chatConsole.writeOutput("Set mode to \"Ghost\"", false);
                    Cacher.put("MODE", "Ghost");
                }
                else {
                    chatConsole.writeOutput("Invalid command", true);
                }
            } catch (Exception e) {
                chatConsole.writeOutput("Something went wrong..", true);
            }
        });

        intercept(HMessage.Direction.TOCLIENT, "Users", this::onUsers);
        intercept(HMessage.Direction.TOSERVER, "GetSelectedBadges", this::maybeBlockPacket);
        intercept(HMessage.Direction.TOSERVER, "GetRelationshipStatusInfo", this::maybeBlockPacket);
        intercept(HMessage.Direction.TOSERVER, "GetExtendedProfile", this::maybeBlockPacket);
        intercept(HMessage.Direction.TOSERVER, "GetHabboGroupDetails", this::maybeBlockPacket);
        intercept(HMessage.Direction.TOCLIENT, "NewFriendRequest", hMessage -> {
            HPacket packet = hMessage.getPacket();
            packet.readInteger();
            if (blockedUsers.contains(packet.readString().toLowerCase())) {
                hMessage.setBlocked(true);
            }
        });

    }

    private void maybeBlockPacket(HMessage hMessage) {
        if (blockedUserIds.containsValue(hMessage.getPacket().readInteger())) {
            hMessage.setBlocked(true);
        }
    }

    private void blockUser(String name) {
        blockedUsers.add(name.toLowerCase());
        sendToServer(new HPacket("IgnoreUser", HMessage.Direction.TOSERVER, name));
        saveBlocksToCache();
    }

    private void saveBlocksToCache() {
        Cacher.put("BLOCKS", new ArrayList<>(blockedUsers));
    }

    private void unblockUser(String name) {
        blockedUsers.remove(name.toLowerCase());
        blockedUserIds.remove(name.toLowerCase());
        sendToServer(new HPacket("UnignoreUser", HMessage.Direction.TOSERVER, name));
        saveBlocksToCache();
    }


    private void onUsers(HMessage hMessage) {
        HPacket oldPacket = hMessage.getPacket();

        HEntity[] users = HEntity.parse(oldPacket);
        List<HEntity> filtered = new ArrayList<>();
        for (HEntity user : users) {
            if (user.getEntityType() == HEntityType.HABBO) {
                if (blockedUsers.contains(user.getName().toLowerCase())) {
                    blockedUserRoomIndexes.add(user.getIndex());
                    blockedUserIds.put(user.getName().toLowerCase(), user.getId());
                    if (mode == Mode.GHOST) {
                        user.setGender(HGender.Male);
                        user.setName("Ghost");
                        user.setFigureId(""); // loads as ghost
                        user.setFavoriteGroup("");
                        user.setMotto("");
                        filtered.add(user);
                    }
                }
                else {
                    filtered.add(user);
                }
            }
        }

        HPacket filteredPacket = HEntity.constructPacket(filtered.toArray(new HEntity[0]), oldPacket.headerId());
        oldPacket.setBytes(filteredPacket.toBytes());
    }
}
