using System.ComponentModel;
using System.Runtime.InteropServices;

namespace Arena.WebView2Prototype;

/// <summary>
/// Applies a Windows Job Object limit to this process and its WebView2 children.
/// The limits are a one-logical-core-equivalent hard CPU cap and a 1 GiB
/// aggregate job memory cap.
/// </summary>
internal static class ProcessResourceLimits
{
    private const int JobObjectExtendedLimitInformationClass = 9;
    private const int JobObjectCpuRateControlInformationClass = 15;

    private const uint JobObjectLimitJobMemory = 0x00000200;
    private const uint JobObjectLimitKillOnJobClose = 0x00002000;
    private const uint JobObjectCpuRateControlEnable = 0x00000001;
    private const uint JobObjectCpuRateControlHardCap = 0x00000004;
    private const ulong MaximumJobMemoryBytes = 1UL << 30;

    // Retain the handle for the lifetime of the process. Windows closes it at
    // process exit; KILL_ON_JOB_CLOSE then cleans up any remaining WebView2 children.
    private static IntPtr _jobHandle;

    public static void Apply()
    {
        IntPtr job = CreateJobObject(IntPtr.Zero, null);
        if (job == IntPtr.Zero)
        {
            throw LastError("CreateJobObject");
        }

        try
        {
            var limits = new ExtendedLimitInformation
            {
                BasicLimitInformation = new BasicLimitInformation
                {
                    LimitFlags = JobObjectLimitJobMemory | JobObjectLimitKillOnJobClose
                },
                JobMemoryLimit = new UIntPtr(MaximumJobMemoryBytes)
            };
            if (!SetInformationJobObject(job, JobObjectExtendedLimitInformationClass,
                    ref limits, (uint)Marshal.SizeOf<ExtendedLimitInformation>()))
            {
                throw LastError("SetInformationJobObject(ExtendedLimitInformation)");
            }

            int logicalProcessorCount = Math.Max(1, Environment.ProcessorCount);
            uint cpuRate = (uint)Math.Max(1, 10_000 / logicalProcessorCount);
            var cpuLimits = new CpuRateControlInformation
            {
                ControlFlags = JobObjectCpuRateControlEnable | JobObjectCpuRateControlHardCap,
                CpuRate = cpuRate
            };
            if (!SetInformationJobObject(job, JobObjectCpuRateControlInformationClass,
                    ref cpuLimits, (uint)Marshal.SizeOf<CpuRateControlInformation>()))
            {
                throw LastError("SetInformationJobObject(CpuRateControlInformation)");
            }

            // Assign before creating WebView2 so its browser/renderer processes
            // inherit these same aggregate limits.
            if (!AssignProcessToJobObject(job, GetCurrentProcess()))
            {
                throw LastError("AssignProcessToJobObject");
            }

            _jobHandle = job;
            job = IntPtr.Zero;
        }
        finally
        {
            if (job != IntPtr.Zero)
            {
                CloseHandle(job);
            }
        }
    }

    private static Win32Exception LastError(string operation)
    {
        int error = Marshal.GetLastWin32Error();
        return new Win32Exception(error, $"Could not apply WebView2 resource limits ({operation}).");
    }

    [DllImport("kernel32.dll", EntryPoint = "CreateJobObjectW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateJobObject(IntPtr jobAttributes, string? name);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetInformationJobObject(
        IntPtr job,
        int jobObjectInformationClass,
        ref ExtendedLimitInformation information,
        uint informationLength);

    [DllImport("kernel32.dll", EntryPoint = "SetInformationJobObject", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetInformationJobObject(
        IntPtr job,
        int jobObjectInformationClass,
        ref CpuRateControlInformation information,
        uint informationLength);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);

    [DllImport("kernel32.dll")]
    private static extern IntPtr GetCurrentProcess();

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseHandle(IntPtr handle);

    [StructLayout(LayoutKind.Sequential)]
    private struct BasicLimitInformation
    {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize;
        public UIntPtr MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass;
        public uint SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct IoCounters
    {
        public ulong ReadOperationCount;
        public ulong WriteOperationCount;
        public ulong OtherOperationCount;
        public ulong ReadTransferCount;
        public ulong WriteTransferCount;
        public ulong OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ExtendedLimitInformation
    {
        public BasicLimitInformation BasicLimitInformation;
        public IoCounters IoInfo;
        public UIntPtr ProcessMemoryLimit;
        public UIntPtr JobMemoryLimit;
        public UIntPtr PeakProcessMemoryUsed;
        public UIntPtr PeakJobMemoryUsed;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct CpuRateControlInformation
    {
        public uint ControlFlags;
        public uint CpuRate;
    }
}
