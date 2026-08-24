package dev.user.homeland.manager;

import dev.user.homeland.model.Homeland;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HomelandStore {

    private final Map<Integer, Homeland> byId = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Integer>> byOwner = new ConcurrentHashMap<>();
    private final Map<String, Integer> byWorldKey = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> byWorldUuid = new ConcurrentHashMap<>();

    public synchronized Homeland upsert(Homeland snapshot) {
        Homeland canonical = byId.putIfAbsent(snapshot.getId(), snapshot);
        if (canonical == null) {
            canonical = snapshot;
        } else if (!canonical.getOwnerUuid().equals(snapshot.getOwnerUuid())
                || !canonical.getName().equals(snapshot.getName())
                || !canonical.getWorldKey().equals(snapshot.getWorldKey())) {
            throw new IllegalArgumentException("Homeland ID maps to a different identity: "
                    + snapshot.getId());
        }
        byOwner.computeIfAbsent(canonical.getOwnerUuid(), ignored -> ConcurrentHashMap.newKeySet())
                .add(canonical.getId());
        refreshIndexes(canonical);
        return canonical;
    }

    public Optional<Homeland> find(UUID ownerUuid, String name) {
        Set<Integer> ids = byOwner.get(ownerUuid);
        if (ids == null) return Optional.empty();
        for (Integer id : ids) {
            Homeland homeland = byId.get(id);
            if (homeland != null && homeland.getName().equalsIgnoreCase(name)) {
                return Optional.of(homeland);
            }
        }
        return Optional.empty();
    }

    public Homeland findByName(String name) {
        for (Homeland homeland : byId.values()) {
            if (homeland.getName().equalsIgnoreCase(name)) return homeland;
        }
        return null;
    }

    public List<Homeland> findByOwner(UUID ownerUuid) {
        Set<Integer> ids = byOwner.get(ownerUuid);
        if (ids == null || ids.isEmpty()) return List.of();
        List<Homeland> result = new ArrayList<>(ids.size());
        for (Integer id : ids) {
            Homeland homeland = byId.get(id);
            if (homeland != null) result.add(homeland);
        }
        return List.copyOf(result);
    }

    public Homeland getByWorldKey(String worldKey) {
        Integer id = byWorldKey.get(worldKey);
        return id == null ? null : byId.get(id);
    }

    public Homeland getByWorldUuid(UUID worldUuid) {
        Integer id = byWorldUuid.get(worldUuid);
        return id == null ? null : byId.get(id);
    }

    public Collection<Homeland> all() {
        return List.copyOf(byId.values());
    }

    public Map<String, Homeland> worldEntries() {
        Map<String, Homeland> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : byWorldKey.entrySet()) {
            Homeland homeland = byId.get(entry.getValue());
            if (homeland != null) result.put(entry.getKey(), homeland);
        }
        return Map.copyOf(result);
    }

    public synchronized void refreshIndexes(Homeland homeland) {
        String baseKey = homeland.getWorldKey();
        byWorldKey.put(baseKey, homeland.getId());
        if (homeland.hasNether()) {
            byWorldKey.put(baseKey + "_nether", homeland.getId());
        } else {
            byWorldKey.remove(baseKey + "_nether", homeland.getId());
        }
        if (homeland.hasEnd()) {
            byWorldKey.put(baseKey + "_the_end", homeland.getId());
        } else {
            byWorldKey.remove(baseKey + "_the_end", homeland.getId());
        }

        byWorldUuid.entrySet().removeIf(entry -> entry.getValue().equals(homeland.getId())
                && !entry.getKey().equals(homeland.getWorldUuid()));
        if (homeland.getWorldUuid() != null) {
            byWorldUuid.put(homeland.getWorldUuid(), homeland.getId());
        }
    }

    public synchronized void updateWorldUuid(Homeland homeland, UUID worldUuid) {
        homeland.setWorldUuid(worldUuid);
        refreshIndexes(homeland);
    }

    public synchronized void remove(Homeland homeland) {
        byId.remove(homeland.getId(), homeland);
        Set<Integer> ownerIds = byOwner.get(homeland.getOwnerUuid());
        if (ownerIds != null) {
            ownerIds.remove(homeland.getId());
            if (ownerIds.isEmpty()) byOwner.remove(homeland.getOwnerUuid(), ownerIds);
        }
        byWorldKey.entrySet().removeIf(entry -> entry.getValue().equals(homeland.getId()));
        byWorldUuid.entrySet().removeIf(entry -> entry.getValue().equals(homeland.getId()));
    }

    public synchronized void clear() {
        byId.clear();
        byOwner.clear();
        byWorldKey.clear();
        byWorldUuid.clear();
    }
}
