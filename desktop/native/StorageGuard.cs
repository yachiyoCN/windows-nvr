using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;

// Reads only directory identity. It cannot create, change, or delete files.
internal static class StorageGuard {
    private const uint InvalidAttributes = 0xFFFFFFFF;
    private const uint ReparsePoint = 0x400;
    private const uint DirectoryAttribute = 0x10;
    [StructLayout(LayoutKind.Sequential)]
    private struct FileInformation {
        public uint Attributes;
        public System.Runtime.InteropServices.ComTypes.FILETIME Creation;
        public System.Runtime.InteropServices.ComTypes.FILETIME Access;
        public System.Runtime.InteropServices.ComTypes.FILETIME Write;
        public uint VolumeSerial;
        public uint SizeHigh;
        public uint SizeLow;
        public uint Links;
        public uint IndexHigh;
        public uint IndexLow;
    }
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern uint GetFileAttributesW(string path);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern SafeFileHandle CreateFileW(string path, uint access, uint sharing,
        IntPtr security, uint creation, uint flags, IntPtr template);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetFileInformationByHandle(SafeFileHandle handle, out FileInformation information);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern uint GetFinalPathNameByHandleW(SafeFileHandle handle, StringBuilder path, uint length, uint flags);

    private static int Main(string[] args) {
        Console.OutputEncoding = new UTF8Encoding(false);
        try {
            if (args.Length != 1) return 2;
            string requested = Path.GetFullPath(args[0]).TrimEnd(Path.DirectorySeparatorChar);
            if (requested.Length == 2 && requested[1] == ':') requested += "\\";
            string volume = Path.GetPathRoot(requested);
            string component = volume;
            RequireOrdinaryDirectory(component);
            foreach (string name in requested.Substring(volume.Length).Split(new[] {'\\', '/'}, StringSplitOptions.RemoveEmptyEntries)) {
                component = Path.Combine(component, name);
                RequireOrdinaryDirectory(component);
            }
            using (SafeFileHandle handle = CreateFileW(requested, 0, 7, IntPtr.Zero, 3,
                    0x02000000 | 0x00200000, IntPtr.Zero)) {
                if (handle.IsInvalid) return 3;
                FileInformation information;
                if (!GetFileInformationByHandle(handle, out information)
                        || (information.Attributes & ReparsePoint) != 0
                        || (information.Attributes & DirectoryAttribute) == 0) return 4;
                StringBuilder final = new StringBuilder(32768);
                uint length = GetFinalPathNameByHandleW(handle, final, (uint) final.Capacity, 0);
                if (length == 0 || length >= final.Capacity) return 5;
                string canonical = final.ToString();
                if (canonical.StartsWith("\\\\?\\UNC\\", StringComparison.OrdinalIgnoreCase)) canonical = "\\\\" + canonical.Substring(8);
                else if (canonical.StartsWith("\\\\?\\", StringComparison.OrdinalIgnoreCase)) canonical = canonical.Substring(4);
                canonical = Path.GetFullPath(canonical);
                if (!String.Equals(requested.TrimEnd('\\'), canonical.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase)) return 6;
                Console.WriteLine("OK");
                Console.WriteLine(information.VolumeSerial.ToString("X8") + ":" + information.IndexHigh.ToString("X8") + information.IndexLow.ToString("X8"));
                Console.WriteLine(Convert.ToBase64String(Encoding.UTF8.GetBytes(canonical)));
            }
            return 0;
        } catch { return 7; }
    }
    private static void RequireOrdinaryDirectory(string path) {
        uint attributes = GetFileAttributesW(path);
        if (attributes == InvalidAttributes || (attributes & ReparsePoint) != 0
                || (attributes & DirectoryAttribute) == 0) throw new IOException("Unsupported directory");
    }
}
