package org.openl.rules.lock;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.openl.util.CollectionUtils;
import org.openl.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shareable, file based locking system.
 *
 * @author Yury Molchan
 */
public class Lock {

    private static final Logger LOG = LoggerFactory.getLogger(Lock.class);
    private static final String READY_LOCK = "ready.lock";

    // lock info
    private static final String USER_NAME = "user";
    private static final String DATE = "date";

    private final Path locksLocation;
    private final Path lockPath;

    Lock(Path locksLocation, String lockId) {
        this.locksLocation = locksLocation;
        this.lockPath = locksLocation.resolve(lockId.replaceAll(":", ""));
    }

    public boolean tryLock(String lockedBy) {
        LockInfo info;
        try {
            info = getInfo();
        } catch (ClosedByInterruptException e) {
            LOG.info("Log retrieving is interrupted. Don't create a lock.", e);
            return false;
        } catch (IOException e) {
            LOG.error("Failed to retrieve lock info.", e);
            return false;
        }
        if (info.isLocked()) {
            // If lockedBy is empty, will return false. Can't lock second time with empty user.
            return !info.getLockedBy().isEmpty() && info.getLockedBy().equals(lockedBy);
        }
        boolean lockAcquired = false;
        if (!Files.exists(lockPath)) {
            Path prepareLock = null;
            try {
                prepareLock = createLockFile(lockedBy);
                lockAcquired = finishLockCreating(prepareLock);
                if (!lockAcquired) {
                    // Delete because of it loos lock
                    Files.delete(prepareLock);
                    deleteEmptyParentFolders();
                }
            } catch (ClosedByInterruptException e) {
                LOG.info("Another thread interrupted IO operation. Cancel lock '{}'.", lockPath);
                deleteLockAndFolders(prepareLock);
                lockAcquired = false;
            } catch (IOException e) {
                LOG.error("Failure of lock creation.", e);
                deleteLockAndFolders(prepareLock);
                lockAcquired = false;
            }
        }
        return lockAcquired;
    }

    public boolean tryLock(String lockedBy, long time, TimeUnit unit) {
        long millisTimeout = unit.toMillis(time);
        long deadline = System.currentTimeMillis() + millisTimeout;
        boolean result = tryLock(lockedBy);
        while (!result && deadline > System.currentTimeMillis()) {
            try {
                TimeUnit.MILLISECONDS.sleep(millisTimeout / 10);
                result = tryLock(lockedBy);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Thread is interrupted. Quit the loop.
                break;
            }
        }
        return result;
    }

    public void forceLock(String lockedBy, long timeToLive, TimeUnit unit) throws InterruptedException, IOException {
        // Time to wait while it's unlocked by somebody
        long timeToWait = timeToLive / 10;
        boolean result = tryLock(lockedBy, timeToWait, unit);
        Instant deadline = Instant.now().plus(timeToLive, toTemporalUnit(unit));
        while (!result) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            LockInfo info;
            try {
                info = getInfo();
            } catch (ClosedByInterruptException e) {
                String message = "Log retrieving is interrupted. Don't create a lock.";
                LOG.debug(message, e);
                throw new InterruptedException(message);
            }
            if (deadline.isBefore(Instant.now())) {
                String message = "Too much time after the lock was created. Looks like the lock is never gonna unlocked. Unlock it ourselves.\n"
                        + "Lock path: {}\n"
                        + "Locked at: {}\n"
                        + "Locked by: {}\n"
                        + "Time to live: {} {}";
                LOG.warn(
                        message,
                        lockPath,
                        info.getLockedAt(),
                        info.getLockedBy(),
                        timeToLive,
                        unit);
                unlock();
            }
            result = tryLock(lockedBy, timeToWait, unit);
        }
    }

    /**
     * TODO: replace this method with unit.toChronoUnit() when we stop supporting java 8
     */
    private TemporalUnit toTemporalUnit(TimeUnit unit) {
        switch (unit) {
            case NANOSECONDS:
                return ChronoUnit.NANOS;
            case MICROSECONDS:
                return ChronoUnit.MICROS;
            case MILLISECONDS:
                return ChronoUnit.MILLIS;
            case SECONDS:
                return ChronoUnit.SECONDS;
            case MINUTES:
                return ChronoUnit.MINUTES;
            case HOURS:
                return ChronoUnit.HOURS;
            case DAYS:
                return ChronoUnit.DAYS;
            default:
                throw new IllegalArgumentException();
        }
    }

    public void unlock() {
        try {
            FileUtils.delete(lockPath.toFile());
            deleteEmptyParentFolders();
        } catch (FileNotFoundException ignored) {
            // Ignored
            // It was already deleted
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void deleteEmptyParentFolders() {
        File file = lockPath.toFile();
        while (!(file = file.getParentFile()).equals(locksLocation.toFile()) && file.delete()) {
            // Delete empty parent folders
        }
    }

    public LockInfo info() {
        try {
            return getInfo();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private LockInfo getInfo() throws IOException {
        Path lock = lockPath.resolve(READY_LOCK);
        if (!Files.isRegularFile(lock)) {
            return LockInfo.NO_LOCK;
        }
        Properties properties = new Properties();
        try (InputStream is = Files.newInputStream(lock)) {
            properties.load(is);
            String userName = properties.getProperty(USER_NAME);
            String stringDate = properties.getProperty(DATE);
            Instant date;
            try {
                date = Instant.parse(stringDate);
            } catch (Exception e) {
                try {
                    // Fallback to the old approach when date was stored in yyyy-MM-dd format
                    // TODO: remove this block on OpenL v5.25.0
                    date = LocalDate.parse(stringDate).atStartOfDay(ZoneOffset.UTC).toInstant();
                } catch (Exception ignored2) {
                    date = Instant.ofEpochMilli(0);
                    LOG.warn("Impossible to parse date {}", stringDate, e);
                }
            }
            return new LockInfo(date, userName);
        }
    }

    Path createLockFile(String userName) throws IOException {
        String userNameHash = Integer.toString(userName.hashCode(), 24);
        Files.createDirectories(lockPath);
        Path lock = lockPath.resolve(userNameHash + ".lock");
        try (Writer os = Files.newBufferedWriter(lock)) {
            os.write("#Lock info\n");
            os.append("user=").append(userName).write('\n');
            os.append("date=").append(Instant.now().toString()).write('\n');
        } catch (Exception e) {
            LOG.info("Can't create lock file '{}'. Delete it.", lock);
            deleteLockAndFolders(lock);
            throw e;
        }
        return lock;
    }

    boolean finishLockCreating(Path lock) throws IOException {
        File[] files = lockPath.toFile().listFiles();
        if (CollectionUtils.isEmpty(files)) {
            // We assume that at this step we must have one current lock file in the folder at least.
            // So, if there is an empty folder, then unlock is happened, and the lock file has been deleted.
            return false;
        }
        Path lockName = lock.getFileName();
        FileTime current = Files.getLastModifiedTime(lock);
        for (File file : files) {
            Path anotherName = file.toPath().getFileName();
            FileTime another = Files.getLastModifiedTime(file.toPath());

            if (current
                    .compareTo(another) > 0 || (current.compareTo(another) == 0 && lockName.compareTo(anotherName) > 0)) {
                return false;
            }
        }
        Files.move(lock, lockPath.resolve(READY_LOCK));
        return true;
    }

    private void deleteLockAndFolders(Path lock) {
        try {
            if (lock != null) {
                Files.delete(lock);
            }
            FileUtils.delete(lockPath.toFile());
            deleteEmptyParentFolders();
        } catch (Exception ex) {
            LOG.error(ex.getMessage(), ex);
        }
    }
}
